;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.climit
  "Concurrencly limiter for RPC."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.util.services :as-alias sv]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.edn :as edn]
   [clojure.spec.alpha :as s]
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.bulkhead :as pbh])
  (:import
   clojure.lang.ExceptionInfo
   com.github.benmanes.caffeine.cache.LoadingCache
   com.github.benmanes.caffeine.cache.CacheLoader
   com.github.benmanes.caffeine.cache.Caffeine
   com.github.benmanes.caffeine.cache.RemovalListener))

(set! *warn-on-reflection* true)

(defn- capacity-exception?
  [o]
  (and (ex/error? o)
       (let [data (ex-data o)]
         (and (= :bulkhead-error (:type data))
              (= :capacity-limit-reached (:code data))))))


(defn- create-cache
  [{:keys [::wrk/executor] :as params} config]
  (let [listener (reify RemovalListener
                   (onRemoval [_ key _val cause]
                     (l/trace :hint "cache: remove" :key key :reason (str cause))))

        loader   (reify CacheLoader
                   (load [_ key]
                     (let [[bkey skey] key]
                       (let [config (get config bkey)]
                         (pbh/create :permits (or (:permits config) (:concurrency config))
                                     :queue (or (:queue config) (:queue-size config))
                                     :timeout (:timeout config)
                                     :executor executor
                                     :type (:type config :semaphore))))))]
    (.. (Caffeine/newBuilder)
        (weakValues)
        (executor executor)
        (removalListener listener)
        (build loader))))

(s/def ::permits ::us/integer)
(s/def ::queue ::us/integer)
(s/def ::timeout ::us/integer)
(s/def ::config
  (s/map-of keyword?
            (s/keys :opt-un [::permits ::queue ::timeout])))

(defmethod ig/prep-key ::rpc/climit
  [_ cfg]
  (assoc cfg ::path (cf/get :rpc-climit-config)))

(s/def ::path ::fs/path)
(defmethod ig/pre-init-spec ::rpc/climit [_]
  (s/keys :req [::wrk/executor ::mtx/metrics ::path]))

(defmethod ig/init-key ::rpc/climit
  [_ {:keys [::path ::mtx/metrics] :as cfg}]
  (when (contains? cf/flags :rpc-climit)
    (if-let [params (some->> path slurp edn/read-string)]
      (do
        (l/info :hint "initializing concurrency limit" :config (str path))
        (us/verify! ::config params)
        {::cache (create-cache cfg params)
         ::config params
         ::mtx/metrics metrics})

      (l/warn :hint "unable to load configuration" :config (str path)))))

(s/def ::cache #(instance? LoadingCache %))
(s/def ::rpc/climit
  (s/nilable
   (s/keys :req [::cache ::config])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn invoke!
  [cache metrics queue key f]
  (let [limiter (.get ^LoadingCache cache [queue key])
        tpoint  (dt/tpoint)
        labels  (into-array String [(name queue)])

        wrapped
        (fn []
          (let [elapsed (tpoint)
                stats   (pbh/get-stats limiter)]
            (l/trace :hint "executed"
                     :name queue
                     :key key
                     :fnh (hash f)
                     :permits (:permits stats)
                     :queue (:queue stats)
                     :max-permits (:max-permits stats)
                     :max-queue (:max-queue stats)
                     :elapsed (dt/format-duration elapsed))
            (mtx/run! metrics
                      :id :rpc-climit-timing
                      :val (inst-ms elapsed)
                      :labels labels)
            (f)))

        measure!
        (fn [stats]
          (mtx/run! metrics
                    :id :rpc-climit-queue
                    :val (:queue stats)
                    :labels labels)
          (mtx/run! metrics
                    :id :rpc-climit-permits
                    :val (:permits stats)
                    :labels labels))]

    (try
      (let [stats (pbh/get-stats limiter)]
        (measure! stats)
        (l/trace :hint "enqueued"
                 :name queue
                 :key key
                 :fnh (hash f)
                 :permits (:permits stats)
                 :queue (:queue stats)
                 :max-permits (:max-permits stats)
                 :max-queue (:max-queue stats))
        (pbh/invoke! limiter wrapped))
      (catch ExceptionInfo cause
        (let [{:keys [type code]} (ex-data cause)]
          (if (= :bulkhead-error type)
            (ex/raise :type :concurrency-limit
                      :code code
                      :hint "concurrency limit reached")
            (throw cause))))

      (finally
        (measure! (pbh/get-stats limiter))))))

(defmacro with-dispatch!
  "Dispatch blocking operation to a separated thread protected with
  the specified semaphore."
  [opts & body]
  (let [f-sym    (gensym "f")
        executor (::executor opts)
        queue    (::queue opts)
        instance (::instance opts)]
    `(let [cache#   (get ~instance :app.rpc.climit/cache)
           metrics# (get ~instance :app.metrics/metrics)
           ~f-sym   (fn [] ~@body)]
       (invoke! cache# metrics# ~queue nil
                ~(if executor
                   `(fn [] (p/await! (px/submit! ~executor ~f-sym)))
                   f-sym)))))

(defmacro with-dispatch
  "Dispatch blocking operation to a separated thread protected with
  the specified semaphore."
  [& params]
  `(with-dispatch! ~@params))

(def noop-fn (constantly nil))

(defn wrap
  [{:keys [::rpc/climit ::mtx/metrics]} f {:keys [::queue ::key-fn] :or {key-fn noop-fn} :as mdata}]
  (if (and (some? climit) (some? queue))
    (if-let [config (get-in climit [::config queue])]
      (let [cache (::cache climit)]
        (l/debug :hint "wrap: instrumenting method"
                 :limit-name (name queue)
                 :service-name (::sv/name mdata)
                 :timeout (:timeout config)
                 :permits (:permits config)
                 :queue (:queue config)
                 :keyed? (some? key-fn))
        (fn [cfg params]
          (invoke! cache metrics queue (key-fn params) (partial f cfg params))))

      (do
        (l/warn :hint "no config found for specified queue" :queue queue)
        f))

    f))
