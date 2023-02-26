;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.access-token
  (:require
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.db :as db]
   [app.main :as-alias main]
   [app.tokens :as tokens]
   [app.worker :as-alias wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.adapter :as yt]
   [yetti.request :as yrq]))

(def header-re #"^Token\s+(.*)")

(defn- get-token
  [request]
  (some->> (yrq/get-header request "authorization")
           (re-matches header-re)
           (second)))

(defn- decode-token
  [props token]
  (when token
    (tokens/verify props {:token token :iss "access-token"})))

(defn- get-token-perms
  [pool token-id]
  (when-not (db/read-only? pool)
    (when-let [token (db/get* pool :access-token {:id token-id} {:columns [:perms]})]
      (some-> (:perms token)
              (db/decode-pgarray #{})))))

(defn- wrap-soft-auth
  "Soft Authentication, will be executed synchronously on the undertow
  worker thread."
  [handler {:keys [::wrk/executor ::main/props]}]
  (letfn [(handle-request [request]
            (try
              (let [token  (get-token request)
                    claims (decode-token props token)]
                (cond-> request
                  (map? claims)
                  (assoc ::id (:tid claims))))
              (catch Throwable cause
                (l/trace :hint "exception on decoding malformed token" :cause cause)
                request)))]

    (fn [request respond raise]
      (let [request (handle-request request)]
        (handler request respond raise)))))

(defn- wrap-authz
  "Authorization middleware, will be executed synchronously on vthread."
  [handler {:keys [::wrk/executor ::db/pool]}]
  (fn [request]
    ;; (prn "access-token" "wrap-authz1" (Thread/currentThread))
    (let [perms (some->> (::id request) (get-token-perms pool))]
      (handler (cond-> request
                 (some? perms)
                 (assoc ::perms perms))))))

(def soft-auth
  {:name ::soft-auth
   :compile (fn [& _]
              (when (contains? cf/flags :access-tokens)
                wrap-soft-auth))})

(def authz
  {:name ::authz
   :compile (fn [& _]
              (when (contains? cf/flags :access-tokens)
                wrap-authz))})
