;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.grid-layout-editor
  (:require
   [app.common.geom.shapes.grid-layout.layout-data :refer [set-sample-data] ]

   [cuerdas.core :as str]
   [app.common.geom.point :as gpt]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes.grid-layout :as gsg]
   [app.common.geom.shapes.points :as gpo]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [rumext.v2 :as mf]))

(mf/defc editor
  {::mf/wrap-props false}
  [props]

  (let [shape   (unchecked-get props "shape")
        objects (unchecked-get props "objects")
        zoom    (unchecked-get props "zoom")
        bounds  (:points shape)]

    (when (ctl/grid-layout? shape)
      (let [children (->> (cph/get-immediate-children objects (:id shape))
                          (remove :hidden)
                          (map #(vector (gpo/parent-coords-bounds (:points %) (:points shape)) %)))

            hv     #(gpo/start-hv bounds %)
            vv     #(gpo/start-vv bounds %)

            width  (gpo/width-points bounds)
            height (gpo/height-points bounds)
            origin (gpo/origin bounds)

            {:keys [row-tracks column-tracks shape-cells]}
            (gsg/calc-layout-data shape children bounds)

            [shape children] (set-sample-data shape children)
            ]

        [:g.grid-editor
         (for [[_ grid-cell] (:layout-grid-cells shape)]
           (let [column (nth column-tracks (dec (:column grid-cell)) nil)
                 row (nth row-tracks (dec (:row grid-cell)) nil)

                 start-p (-> origin
                             (gpt/add (hv (:distance column)))
                             (gpt/add (vv (:distance row))))

                 end-p (-> start-p
                           (gpt/add (hv (:value column)))
                           (gpt/add (vv (:value row))))]

             [:rect.cell-editor {:x (:x start-p)
                                 :y (:y start-p)
                                 :width (- (:x end-p) (:x start-p))
                                 :height (- (:y end-p) (:y start-p))
                                 :style {:stroke "#DB00FF"
                                         :stroke-dasharray (str/join " " (map #(/ % zoom) [0 8]) )
                                         :stroke-linecap "round"
                                         :stroke-width (/ 2 zoom)}
                                 }]))]))))
