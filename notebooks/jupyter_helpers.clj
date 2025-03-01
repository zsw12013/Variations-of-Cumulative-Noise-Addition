(ns jupyter-helpers
  (:require [clojure.string :as string]
            [ppdsp.utils :refer [map-vals percent
                                seq-contains? *debug*
                                safe-division
                                std-dev mean median
                                format-number
                                format-number-scientific]]
            [clojure.core.matrix :as m]
            [ppdsp.masking.evaluation
             :refer [unknown-record-displacement
                     masking-experiment
					           masking-experiment-cycles ;;waruni
                     test-classification-accuracy
                    ;; test-classification-accuracy-cycles ;;waruni
                     prob-eps-privacy-breach]]
            [clojure.math.numeric-tower :refer [sqrt expt]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv] ;;waruni
           ;; [semantic-csv.impl.core :as impl :refer [?>>]] ;;waruni
            [clojure.java.shell :as sh]
            [clojure.pprint :refer [pprint]])
  (:import [com.twosigma.beakerx.table TableDisplay]
           [com.twosigma.beakerx.chart ToolTipBuilder]
           [com.twosigma.beakerx.chart.legend LegendPosition LegendLayout]
           [com.twosigma.beakerx.chart.xychart Plot]
           [com.twosigma.beakerx.chart.xychart.plotitem Line Points ShapeType]
           [com.twosigma.beakerx.jvm.object GridOutputContainerLayoutManager OutputContainer]
           [java.util.concurrent Executors]
           [java.awt Color]
           [com.twosigma.beakerx.widget HTML]))

(defn save-data [filename data]
  (io/make-parents filename)
  (with-open [w (io/writer filename)]
    (binding [*out* w]
      (pr data)))
  data)

;;waruni
(defn save-data-append [filename data]
  (io/make-parents filename)
  (with-open [w (io/writer filename :append true)]
    (binding [*out* w]
      (pr data )))
  data)

(defn save-data-append-newline [filename data]
  (io/make-parents filename)
  (with-open [w (io/writer filename :append true)]
    (binding [*out* w]
      (println data )))
  data)

(defn write-csv-lines
  [filename value]
  (with-open [writer (io/writer filename)]
   ; (doseq [line lines]
      (.write writer value)
     ; )
    )
  )



(defn read-data [filename]
  (with-open [reader (io/reader filename)]
   (edn/read (java.io.PushbackReader. reader)))
  
  )

;;;;;;;;;waruni
(defn load-data
  [filename]
  (with-open [reader (java.io.PushbackReader. (io/reader filename))]
    (edn/read reader)))

(defn- pmap-pool [thread-count func coll]
  (let [pool (Executors/newFixedThreadPool thread-count)
        results (->> coll
                     (map (fn [item]
                            (fn [] (func item))))
                     (.invokeAll pool)
                     (map #(.get %))
                     (doall))]
    (.shutdown pool)
    results))

(defn load-experiment
  [output-dir experiment-label]
  (let [filename (str output-dir "/" experiment-label ".edn")]
    (with-open [reader (java.io.PushbackReader. (io/reader filename))]
      (edn/read reader))))

(defn display-html [s]
  (doto (HTML.)
    (.setValue s)
    (.display)))

(defn display-table
  [seq-of-maps]
  (->> seq-of-maps
       (map
        (fn [row-map]
          (->> row-map
               (map #(vector (str (first %)) (second %)))
               (into (sorted-map)))))
       (TableDisplay.)
       (.display)))

(defn plot
  [plotitem-constructor series
   & {:keys [y-label x-label plot-title init-width init-height colours markers tooltip-fn outline-colour]}]
  (let [plot (doto (Plot.)
               (.setLegendPosition LegendPosition/TOP)
               (.setLegendLayout LegendLayout/HORIZONTAL)
               (.setXLabel (or x-label ""))
               (.setYLabel (or y-label ""))
               (.setTitle (or plot-title ""))
               (.setInitWidth (or init-width 640))
               (.setInitHeight (or init-height 480)))
        colours (concat (or colours
                            [(Color/decode "#D12249")
                             (Color/decode "#809AE5")
                             (Color/decode "#3F4D62")])
                        (repeat nil))
        markers (concat (or markers []) (repeat nil))]
    (doseq [[[label points] colour {:keys [shape size]}] (map vector series colours markers)]
      (let [plotitem (doto (plotitem-constructor)
                       (.setX (map (comp float first) points))
                       (.setY (map (comp float second) points))
                       (.setDisplayName (str label)))]
        (when tooltip-fn
          (.setToolTip plotitem
                       (proxy [ToolTipBuilder] []
                         (call [& args]
                           (apply tooltip-fn args))
                         (getMaximumNumberOfParameters []
                           5))))
        (when colour
          (.setColor plotitem colour))
        (when shape
          (.setShape plotitem shape))
        (when size
          (.setSize plotitem size))
        (when outline-colour
          (.setOutlineColor plotitem outline-colour))
        (.add plot plotitem)))
    plot))

(def line-plot (partial plot #(Line.)))
(def scatter-plot (partial plot #(Points.)))

(defn plot-lines
  [bin-size lines
   & {:keys [y-label x-label plot-title init-width init-height colours]}]
  (let [plot (doto (Plot.)
               (.setLegendPosition LegendPosition/TOP)
               (.setLegendLayout LegendLayout/HORIZONTAL)
               (.setXLabel (or x-label ""))
               (.setYLabel (or y-label ""))
               (.setTitle (or plot-title ""))
               (.setInitWidth (or init-width 640))
               (.setInitHeight (or init-height 480)))
        colours (concat (or colours
                            [(Color/decode "#000000");;light black (Color/decode "#3F4D62")
                             (Color/decode "#FF0000") ;;Red (Color/decode "#D12249")
                             (Color/decode "#0000FF") ;;blue (Color/decode "#809AE5")
                             ]) 
                        (repeat nil))]
    (doseq [[[label points] colour] (map vector lines colours)]
      (let [points (->> points (partition bin-size) (map #(/ (reduce + %) (count %))))
            line (doto (Line.)
                   (.setX (map #(* bin-size %) (range (count points))))
                   (.setY (map float points))
                   (.setDisplayName (str label)))]
        (when colour
          (.setColor line colour))
        (.add plot line)))
    plot))

;; Masking Evaluation

(defn display-masking-error-plots
  [flat-results vertical-grouping horizontal-grouping series-grouping
   & {:keys [plot-width plot-height]
      :or {plot-width 800 plot-height 800}}]
  (let [plots (for [[plot-keys plot-results] (->> flat-results
                                                  (group-by (juxt vertical-grouping horizontal-grouping))
                                                  (sort-by first))]
                (let [plot (doto (Plot.)
                             (.setTitle (str (zipmap [vertical-grouping horizontal-grouping] plot-keys)))
                             (.setYLabel "Relative Error")
                             (.setXLabel "Gap Between Unknown and Known")
                             (.setXBound [0 (* 1.1 (apply max (map unknown-record-displacement plot-results)))])
                             (.setYBound [0 (* 1.1 (apply max (map :relative-error plot-results)))])
                             (.setLegendPosition LegendPosition/TOP)
                             (.setLegendLayout LegendLayout/HORIZONTAL)
                             (.setInitWidth plot-width)
                             (.setInitHeight plot-height)
                             (.setShowLegend false))]
                  (doseq [[strategy points] (->> plot-results
                                                 (group-by (juxt series-grouping))
                                                 (sort-by first))]
                    (.add plot
                          (doto (Points.)
                            (.setDisplayName (str strategy))
                            (.setX (map unknown-record-displacement points))
                            (.setY (map :relative-error points))
                            (.setColor (Color. 0xda 0x4e 0x6d)))))
                  plot))
        og (doto (OutputContainer.)
             (.setLayoutManager (GridOutputContainerLayoutManager. (count (distinct (map horizontal-grouping flat-results))))))]
    (doseq [plot plots]
      (.addItem og plot))
    og))

(defn run-masking-experiments
  [{:keys [dataset output-file projection-feature-counts
           projection-sigmas independent-noise-sigmas
           cumulative-noise-sigmas translations cycle-sizes attack-count
           attempt-count known-record-counts known-record-ranges
           known-record-range-position flat-record-length
           attack-strategies threads-per-configuration
           classifier-fns threads-per-evaluation seed evaluations]}]
  (time
   (let [configurations (for [projection-feature-count projection-feature-counts
                              cumulative-noise-sigma cumulative-noise-sigmas
                              independent-noise-sigma independent-noise-sigmas
                              translation translations
							                cycle-size cycle-sizes                                ;;waruni
                             ;;attack-count attack-count                   ;;waruni
                              projection-sigma projection-sigmas]
                          {:projection-feature-count projection-feature-count
                           :cumulative-noise-sigma cumulative-noise-sigma
                           :independent-noise-sigma independent-noise-sigma
                           :translation translation
						               :cycle-size cycle-size          ;;waruni
                           :projection-sigma projection-sigma})
         results (pmap-pool threads-per-configuration
                            (fn [{:keys [projection-feature-count cumulative-noise-sigma
                                         independent-noise-sigma translation cycle-size projection-sigma]}]
                              (masking-experiment
                               :raw-dataset dataset
                               :projection-features projection-feature-count
                               :projection-sigma projection-sigma
                               :independent-noise-sigma independent-noise-sigma
                               :cumulative-noise-sigma cumulative-noise-sigma
                               :translation translation
							                 :cycle-size cycle-size  ;;waruni 
                               :classifier-fns classifier-fns
                               :privacy-evaluation-configuration
                               {:attack-count attack-count
                                :attempt-count attempt-count
								                :cycle-size cycle-size        ;;waruni
                                :flat-record-length flat-record-length
                                :evaluation-threads threads-per-evaluation
                                :known-record-counts known-record-counts
                                :known-record-ranges known-record-ranges
                                :known-record-range-position known-record-range-position
                                :attack-strategies attack-strategies}
                               :seed seed
                               :evaluations evaluations))
                            configurations)
         output {:original {:accuracy (map-vals #(test-classification-accuracy % dataset)
                                                classifier-fns)}
                 :results results}]
     (save-data output-file output)
     nil)))

(defn plot-masked-accuracy
  [original-accuracy result
   & {:keys [plot-width plot-height partition-size]}]
  (doseq [classifier (-> result :accuracy keys)
          :let [result-accuracy (-> result :accuracy)]]
    (.display
     (plot-lines (or partition-size 100)
                 (map-vals
                  (fn [acc]
                    (map #(if (= (:class %) (:truth %)) 1 0)
                         (:raw-results (get acc classifier))))
                  {:original original-accuracy
                   :masked result-accuracy})
                 :y-label "Accuracy"
                 :x-label "Records"
                 :plot-title (str classifier)
                 :init-width (or plot-width 1200)
                 :init-height plot-height))))

(defn noise-accuracy-plot
  [results-cumulative results-cumulative-cycles results-cumulative-logistic classifier  ;;results-rp-only     results-independent
   & {:keys [init-width init-height partition-size]}]
  (let [  max-cumulative-sigma (apply max (map :cumulative-noise-sigma results-cumulative))
        max-cumulative-cycles-sigma (apply max (map :cumulative-noise-sigma results-cumulative-cycles))
       ;; max-independent-sigma (apply max (map :independent-noise-sigma results-independent))
        max-logistic-sigma (apply max (map :cumulative-noise-sigma results-cumulative-logistic ))
        result->accuracies
        (fn [result]
          (->> result
               :accuracy
               classifier
               :raw-results
               (map #(if (= (:class %) (:truth %)) 1 0))
           )
         )
        series {;;"No additive noise (RP only)"
               ;; (->> results-rp-only
                    ;; first
                    ;; result->accuracies)
			                (str "Cumulative noise without Cycles; sigma=" (format "%.3e" max-cumulative-sigma))
			                (->> results-cumulative
			                     (filter #(= max-cumulative-sigma (:cumulative-noise-sigma %)))
			                     first
			                     result->accuracies
			                 )
			                (str "Cumulative noise with Cycles; sigma=" (format "%.3e" max-cumulative-cycles-sigma))
			                (->> results-cumulative-cycles
			                     (filter #(= max-cumulative-cycles-sigma (:cumulative-noise-sigma %)))
			                     first
			                     result->accuracies
			                )
			                (str " Logistic Cumulative noise; sigma=" (format "%.3e" max-logistic-sigma))
			                (->> results-cumulative-logistic
			                     (filter #(= max-logistic-sigma (:cumulative-noise-sigma %)))
			                     first
			                     result->accuracies
			                )
			                ;;(str "Independent noise; sigma=" max-independent-sigma)
			                ;;(->> results-independent
			                    ;; (filter #(= max-independent-sigma (:independent-noise-sigma %)))
			                    ;; first
			                     ;;result->accuracies)
              }
        ]
    
    
    (doto (plot-lines (or partition-size 500)
                      series
                      :y-label "Accuracy"
                      :x-label "Records"
                      :init-width (or init-width 1200)
                      :init-height (or init-height 400)
                      :colours [(Color. 0x00 0x00 0x00) ;;without cycles
                                 (Color. 0xda 0x4e 0x6d);;REd  ;;cumulative with cycles
                                (Color. 0x99 0xae 0xea) ;;Blue ;;logistic
                               ]
           ) 
         (.setXBound [0 (-> series vals first count)])
         (.setShowLegend false)
      )
   )
)

(defn grid-plot-results
  [flat-results vertical-grouping horizontal-grouping plot-fn
   & {:keys [plot-width plot-height]
      :or {plot-width 800
           plot-height 500}}]
  (let [plots (for [[plot-keys plot-results] (->> flat-results
                                                  (group-by (juxt vertical-grouping horizontal-grouping))
                                                  (sort-by first))
                    :let [plot (plot-fn plot-keys plot-results)]]
                (doto plot
                  (.setTitle (or (.getTitle plot)
                                 (str (zipmap [vertical-grouping horizontal-grouping] plot-keys))))
                  (.setLegendPosition LegendPosition/TOP)
                  (.setLegendLayout LegendLayout/HORIZONTAL)
                  (.setInitWidth plot-width)
                  (.setInitHeight plot-height)))
        og (doto (OutputContainer.)
             (.setLayoutManager (GridOutputContainerLayoutManager. (count (distinct (map horizontal-grouping flat-results))))))]
    (doseq [plot plots]
      (.addItem og plot))
    og))

(defn attack-strategy-comparison-plots
  [flat-results grouping epsilon
   & {:keys [plot-width plot-height colours markers show-legend?]
      :or {plot-width 300
           plot-height 300
           ;; http://colorbrewer2.org/#type=qualitative&scheme=Dark2&n=7
           colours [(Color/decode "#1f78b4")
                    (Color/decode "#33a02c")
                    (Color/decode "#fb9a99")
                    (Color/decode "#a6cee3")
                    (Color/decode "#b2df8a")
                    #_(Color. 0xe7 0x29 0x8a)
                    #_(Color. 0xe6 0xab 0x02)
                    #_(Color. 0x75 0x70 0xb3)
                    #_(Color. 0x66 0xa6 0x1e)
                    #_(Color. 0x1b 0x9e 0x77)
                    #_(Color. 0xa6 0x76 0x1d)
                    #_(Color. 0xd9 0x5f 0x02)]
           markers [{:shape ShapeType/SQUARE :size 30}
                    {:shape ShapeType/CIRCLE :size 28}
                    {:shape ShapeType/DCROSS :size 25}
                    {:shape ShapeType/DIAMOND :size 25}
                    {:shape ShapeType/TRIANGLE :size 23}
                    #_{:shape ShapeType/DOWNTRIANGLE :size 10}
                    #_{:shape ShapeType/CROSS :size 8}]
           show-legend? true}}]
  (let [y-bound (->> flat-results
                     (group-by (juxt grouping :strategy :known-record-count))
                     (vals)
                     (map #(prob-eps-privacy-breach % epsilon))
                     (apply max)
                     (* 1.1))]
    (grid-plot-results flat-results
                       :all grouping
                       (fn [[_ grouping-value] plot-results]
                         (let [series (->> plot-results
                                           (group-by :strategy)
                                           (map-vals
                                            (fn [strategy-results]
                                              (->> strategy-results
                                                   (group-by :known-record-count)
                                                   (map-vals #(prob-eps-privacy-breach % epsilon))
                                                   (sort-by first))))
                                           (sort-by first))]
                           (doto (scatter-plot series
                                               :colours colours
                                               :outline-colour (Color/black)
                                               :markers markers)
                             (.setShowLegend show-legend?)
                             (.setTitle (str [grouping grouping-value]))
                             (.setXLabel "Known record count")
                             (.setYLabel (str "Prob. of ε-privacy breach; ε=" epsilon))
                             (.setYBound -0.01 y-bound))))
                       :plot-width plot-width
                       :plot-height plot-height)))

(defn generate-tradeoff-rows
  [results tradeoff-variable classifier attack-strategy epsilon]
  (let [results (filter #(> (tradeoff-variable %) 0) results)
        tradeoff-levels (-> (map tradeoff-variable results)
                            distinct
                            sort
                            (zipmap (map inc (range))))]
    (apply concat
           (for [result results
                 :let [tradeoff (-> result tradeoff-variable)
                       accuracy (-> result :accuracy classifier :accuracy)
                       ;;idno (-> result :accuracy classifier :raw-results :id) ;;waruni
                       ]
                 ]
             (for [evaluation (-> result :privacy :evaluations)
                   :let [known-record-count (-> evaluation :known-record-count)
                         privacy (as-> evaluation $
                                   (:attack-results $)
                                   (map (comp attack-strategy :strategies) $)
                                   (prob-eps-privacy-breach $ epsilon))
                         classification-error (- 1 (double accuracy))]]
               {:known-record-count known-record-count
                :tradeoff-var tradeoff-variable
                :tradeoff tradeoff
               ;; :idno idno   ;;waruni
                :tradeoff-level (get tradeoff-levels tradeoff)
                :classification-error classification-error
                :privacy (double privacy)})))))

(defn get-all-tradeoff-rows
  [results-cumulative results-cumulative-cycles results-cumulative-logistic classifier ;;results-rp-only  ;;results-independent
   cumulative-attack-strategy cumulative-cycles-attack-strategy logistic-attack-strategy epsilon]      ;;independent-attack-strategy
  (concat ;;(map #(assoc % :mask-type "RP Only") (generate-tradeoff-rows results-rp-only :projection-features classifier :a-rp epsilon))
          (map #(assoc % :mask-type "Cumulative Noise without Cycles") (generate-tradeoff-rows results-cumulative :cumulative-noise-sigma classifier cumulative-attack-strategy epsilon))
          (map #(assoc % :mask-type "Cumulative Noise with Cycles") (generate-tradeoff-rows results-cumulative-cycles :cumulative-noise-sigma classifier cumulative-cycles-attack-strategy epsilon))
          (map #(assoc % :mask-type "Logistic Noise") (generate-tradeoff-rows results-cumulative-logistic :cumulative-noise-sigma classifier logistic-attack-strategy epsilon))
          ;;(map #(assoc % :mask-type "Independent Noise") (generate-tradeoff-rows results-independent :independent-noise-sigma classifier independent-attack-strategy epsilon))
))

(defn accuracy-privacy-tradeoff-legend
  [cumulative-noise-sigmas] ;;independent-noise-sigmas
  (let [shapes ["circle" "triangle" "square" "diamond" "cross"]
        cell-count (max ;;(count independent-noise-sigmas)
                        (count cumulative-noise-sigmas))
        header-cells (map #(str "<th>Noise Level " (inc %) "</th>")
                          (range cell-count))
       ;; independent-noise-cells (map #(str "<td><img src=\"assets/blue-" %2 ".png\"> σ = " (format "%.1e" %1) "</td>")
                                     ;;independent-noise-sigmas shapes)
        cumulative-noise-cells (map #(str "<td><img src=\"assets/black-" %2 ".png\"> σ = " (format "%.1e" %1) "</td>")
                                    cumulative-noise-sigmas shapes)
        cumulative-cycles-noise-cells (map #(str "<td><img src=\"assets/red-" %2 ".png\"> σ = " (format "%.1e" %1) "</td>")
                                    cumulative-noise-sigmas shapes)
        logistic-cumulative-noise-cells (map #(str "<td><img src=\"assets/blue-" %2 ".png\"> σ = " (format "%.1e" %1) "</td>")
                                    cumulative-noise-sigmas shapes)]
    (str "<style>
           .custom-legend {
               margin: auto;
           }
           .custom-legend img {
               height: 15px !important;
               position: relative;
               top: -2px;
           }
           .custom-legend td,
           .custom-legend th {
               padding: 5px;
               line-height: 15px;
           }
          </style>
          <table class=\"custom-legend\">
            <tr>
              <th>Masking Method</th>
              " (string/join header-cells) "
            </tr>
             <tr>
              <td>RP + Cumulative Noise without Cycles</td>
              " (string/join cumulative-noise-cells) "
            </tr>
            <tr>
              <td>RP + Cumulative Noise with Cycles</td>
              " (string/join cumulative-cycles-noise-cells) "
            </tr>
            <tr>
              <td>RP + Logistic Cumulative Noise</td>
              " (string/join logistic-cumulative-noise-cells) "
            </tr>
            
          </table>")))

(defn accuracy-privacy-tradeoff-comparison
  [results-cumulative results-cumulative-cycles results-cumulative-logistic ;;results-rp-only ;;results-independent 
   classifier cumulative-attack-strategy cumulative-cycles-attack-strategy logistic-attack-strategy  ;;independent-attack-strategy
   epsilon & {:keys [plot-width plot-height]}]
  (let [tradeoff-rows (get-all-tradeoff-rows results-cumulative results-cumulative-cycles results-cumulative-logistic ;;results-rp-only   ;; results-independent
                                             classifier cumulative-attack-strategy cumulative-cycles-attack-strategy logistic-attack-strategy      ;;  independent-attack-strategy 
                                             epsilon)
        x-bound [(max (- 0.02) (- (apply min (map :classification-error tradeoff-rows)) 0.05))
                 (* 1.1 (apply max (map :classification-error tradeoff-rows)))]
        y-bound [(max (- 0.02) (- (apply min (map :privacy tradeoff-rows)) 0.05))
                 (* 1.1 (apply max (map :privacy tradeoff-rows)))]
        colours [(Color. 0x00 0x00 0x00) ;;(Color. 0x1b 0x21 0x26)
                 (Color. 0xda 0x4e 0x6d) ;;(Color. 0xd1 0x22 0x49)
                 (Color. 0x99 0xae 0xea);; (Color. 0x80 0x9a 0xe5)
                 ]
        markers [{:shape ShapeType/CIRCLE :size 8}
                 {:shape ShapeType/TRIANGLE :size 10}
                 {:shape ShapeType/SQUARE :size 8}
                 {:shape ShapeType/DIAMOND :size 10}
                 {:shape ShapeType/CROSS :size 8}]]
    (grid-plot-results tradeoff-rows
                       :all :known-record-count
                       (fn [[_ known-record-count] plot-rows]
                         (let [plot (doto (Plot.)
                                      (.setTitle (str "Known Record Count: " known-record-count))
                                      (.setShowLegend false)
                                      (.setXLabel "Classification Error")
                                      (.setYLabel (str "Prob. of ε-privacy breach; ε=" epsilon))
                                      (.setXBound x-bound)
                                      (.setYBound y-bound))
                               series (group-by :mask-type plot-rows)]
                           (doseq [[[mask-type rows] colour] (map vector series colours)]
                             (doseq [[row {:keys [shape size]}] (map vector
                                                                     (sort-by #(:tradeoff %) rows)
                                                                     markers)]
                               (.add plot
                                     (doto (Points.)
                                       (.setX [(float (:classification-error row))])
                                       (.setY [(float (:privacy row))])
                                       (.setShape shape)
                                       ;;(.setSize (if (= (:mask-type row) "RP Only") 12 size))
                                       (.setColor colour)
                                       (.setOutlineColor (Color/black))
                                       (.setDisplayName (str mask-type))))))
                           plot))
                       :plot-width (or plot-width 500)
                       :plot-height (or plot-width 500))))

(defn accuracy-privacy-tradeoff
  [results-cumulative results-cumulative-cycles results-cumulative-logistic ;;results-rp-only   ;;results-independent
   classifier cumulative-attack-strategy cumulative-cycles-attack-strategy logistic-attack-strategy                         ;;independent-attack-strategy
   epsilons & {:keys [square-distance? row-per-noise-level? known-record-count]}]
  (let [eps-mask-perfs
        (zipmap
         epsilons
         (for [epsilon epsilons]
		           (cond->> (get-all-tradeoff-rows results-cumulative results-cumulative-cycles results-cumulative-logistic ;;results-independent
		                                           classifier      ;; results-rp-only
		                                           cumulative-attack-strategy cumulative-cycles-attack-strategy
		                                           logistic-attack-strategy     ;;independent-attack-strategy
		                                           epsilon)
		             ;; Optionally restrict to a particular known-record-count
		             known-record-count (filter #(= (:known-record-count %) known-record-count))
		             ;; Group either by mask-type or mask-type+noise-level
		             (not row-per-noise-level?) (group-by :mask-type)
		             row-per-noise-level? (group-by #(if (= (:mask-type %) "RP Only")
		                                               (:mask-type %)
		                                               (str (:mask-type %) " - Level " (:tradeoff-level %)))
                                       )
		             ;; Compute square distance
		             true (map-vals (fn [rows] (map #(+ (expt (:classification-error %) 2)
		                                                (expt (:privacy %) 2))
		                                            rows)))
		             ;; Optionally convert to distance
		             (not square-distance?) (map-vals #(map sqrt %))
		             ;; Take mean of any multiple rows (only if grouping by
		             ;; mask-type or averaging over many known-record-counts)
		             true (map-vals mean)
              )
           )
         )
           mask-keys (-> eps-mask-perfs vals first keys)
       ]
    (sort-by
     #(get % "Mask")
     (for [mask-key mask-keys]
       (let [perf-cols (->> eps-mask-perfs
                            (map (fn [[epsilon mask-perfs]]
                                   {(str "Performance; e=" epsilon) (get mask-perfs mask-key)}))
                            (apply merge))]
         (apply merge
                {"Mask" mask-key
                 "_Mean performance" (mean (vals perf-cols))}
                perf-cols
         )
        )
      )
    )
   )
)


;;WAruni -->accuracy per cycle
(defn accuracy-per-cycle
  [results-cumulative results-cumulative-cycles results-cumulative-logistic  cycle-sizes record-count  ;;results-rp-only   ;;results-independent
   classifier cumulative-attack-strategy cumulative-cycles-attack-strategy logistic-attack-strategy                         ;;independent-attack-strategy
   epsilons & {:keys [square-distance? row-per-noise-level? known-record-count]}
  ]
 (def  j 0)
    (loop [i 1]
      (when (= i record-count)
        (if (and (> i (* j (* cycle-sizes 2)) ) (<= i (* (* cycle-sizes 2) (+ j 1))))
          (let [cycle (group-by(+ j 1))
               ;; cycle-no (group-by cycle)
                ] )
           (swap! j inc)
         )
      (recur (+ i 1))   
      )
    )
  (let [ 
         eps-mask-perfs
        (zipmap
         epsilons
         (for [epsilon epsilons ]
		           (cond->> (get-all-tradeoff-rows results-cumulative results-cumulative-cycles results-cumulative-logistic ;;results-independent   ;; results-rp-only
		                                          classifier cumulative-attack-strategy cumulative-cycles-attack-strategy logistic-attack-strategy     ;;independent-attack-strategy
		                                          epsilon)
		             ;; Optionally restrict to a particular known-record-count
		              known-record-count (filter #(= (:known-record-count %) known-record-count))
		             ;; Group either by mask-type or mask-type+noise-level
		               (not row-per-noise-level?) (group-by :mask-type)
		             row-per-noise-level? (group-by #(if (= (:mask-type %) "RP Only")
		                                               (:mask-type %)
		                                               (str (:mask-type %) " - Level " (:tradeoff-level %)))
                                       )
              
		           true (map-vals (fn [rows] (map #(:classification-error %)  rows)
                               )
                     )
              )
           )
         ) 
        
           mask-keys (-> eps-mask-perfs vals first keys)
       ]
    (sort-by
     #(get % "Mask")
     (for [mask-key mask-keys ]
       (let [  perf-cols (->> eps-mask-perfs
                            (map (fn [[ mask-perfs]]
                                   {(str "cycle No:"  ) (get mask-perfs mask-key)}))
                   ;; {(str "Performance; e=" epsilon) (get mask-perfs mask-key)}))
             (apply merge))
             
             
              ]
		         (apply merge
		                {"Mask - Classification Error" mask-key
		               ;;"_Mean performance" (mean (vals perf-cols))
                    }
		               perf-cols
		         )
        )
      )
    )
   )
  
)
;;WAruni -->accuracy per cycle
(defn attack-strategy-comparison
  [flat-results epsilons
   & {:keys [known-record-count independent-noise-sigma
             cumulative-noise-sigma]}]
  (->> (cond->> flat-results
         known-record-count (filter #(= (:known-record-count %) known-record-count))
         independent-noise-sigma (filter #(= (:independent-noise-sigma %) independent-noise-sigma))
         cumulative-noise-sigma (filter #(= (:cumulative-noise-sigma %) cumulative-noise-sigma)))
       (group-by :strategy)
       (sort-by first)
       (map
        (fn [[strategy results]]
          (let [eps-privacies (->> epsilons
                                   (map #(float (prob-eps-privacy-breach results %1)))
                                   (zipmap (map #(str "Mean prob. of ε-privacy breach; ε=" %) epsilons)))]
            (merge {"0_Strategy" (str strategy)
                    "Total Mean prob. of ε-privacy breach" (mean (vals eps-privacies))}
                   eps-privacies))))))

(defn round-known-record-counts
  [results]
  (letfn [(round-up [krc]
            (int (Math/ceil krc)))]
    (->> results
         (map
          (fn [result]
            (-> result
                (update-in [:privacy :configuration :known-record-counts]
                           #(map round-up %))
                (update-in [:privacy :evaluations]
                           (fn [evaluations]
                             (map #(update % :known-record-count round-up)
                                  evaluations)))))))))
								  
	(defn run-masking-experiments-cycles
  [{:keys [dataset output-file projection-feature-counts
           projection-sigmas independent-noise-sigmas 
           cumulative-noise-sigmas translations cycle-sizes attack-count 
           attempt-count known-record-counts known-record-ranges
           known-record-range-position growth-rate-k maximum-fn-value flat-record-length
           attack-strategies threads-per-configuration
           classifier-fns threads-per-evaluation seed evaluations]}]
  (time
   (let [configurations (for [projection-feature-count projection-feature-counts
                              cumulative-noise-sigma cumulative-noise-sigmas
                              independent-noise-sigma independent-noise-sigmas
                              translation translations
							                   cycle-size cycle-sizes                                ;;waruni
                             ;;attack-count attack-count                   ;;waruni
                              projection-sigma projection-sigmas]
                          {:projection-feature-count projection-feature-count
                           :cumulative-noise-sigma cumulative-noise-sigma
                           :independent-noise-sigma independent-noise-sigma
                           :translation translation
						               :cycle-size cycle-size          ;;waruni
                           :projection-sigma projection-sigma})
         results (pmap-pool threads-per-configuration
                            (fn [{:keys [projection-feature-count cumulative-noise-sigma
                                         independent-noise-sigma translation cycle-size projection-sigma]}]
                              (masking-experiment-cycles
                               :raw-dataset dataset
                               :projection-features projection-feature-count
                               :projection-sigma projection-sigma
                               :independent-noise-sigma independent-noise-sigma
                               :cumulative-noise-sigma cumulative-noise-sigma
                               :translation translation
							                 :cycle-size cycle-size  ;;waruni
                               :maximum-fn-value maximum-fn-value
                               :growth-rate-k growth-rate-k
                               :classifier-fns classifier-fns
                               :privacy-evaluation-configuration
                               {:attack-count attack-count
                                :attempt-count attempt-count
								                :cycle-size cycle-size        ;;waruni
                                :flat-record-length flat-record-length
                                :evaluation-threads threads-per-evaluation
                                :known-record-counts known-record-counts
                                :known-record-ranges known-record-ranges
                                :known-record-range-position known-record-range-position
                                :attack-strategies attack-strategies}
                               :seed seed
                               :evaluations evaluations))
                            configurations)
         output {:original {:accuracy (map-vals #(test-classification-accuracy % dataset)
                                                classifier-fns )}
                 :results results}]
     (save-data output-file output)
     nil)))
					  
;;Recording accuracy for each data sample (waruni)
(defn accuracy-updating-cycles [results accuracy-cycles-outputfile record-count full-cycle details-outputfile]
 (def counter (atom 0))
 (def x (atom 0))
 (def details-vec (atom {:sample-accuracy {}}))
  (loop [i 1]
    (when (<= i record-count)
					    (if (= i (+ 1 (* full-cycle (+ @x 1)))) ;;to get the relative id according to the cycle
						     (do
					         (swap! x inc)
					         (reset! counter 0)
				        )
					    )
		    (let[ cnt-value (if (=(get-in results [i :class]) (get-in results [i :truth]))
		                        (swap! counter inc)
		                        (+ @counter 0)    )
		                      acc-value  (/ cnt-value (- i (* full-cycle @x))) ;;divided by the relative id for the cycle
                         ;; acc-value  (/ cnt-value i)  ;;divided by the actual id
						              double-value (double acc-value)
		           ]
        
                (swap! details-vec assoc-in [:sample-accuracy  i] {:id i :value double-value}) ;;appending values return from each iteration to a atom vector
		                  ;;(save-data-append-newline accuracy-cycles-outputfile double-value) ;;for excel operations    
		     )
      (recur (+ i 1))
    )
  )
	(save-data details-outputfile @details-vec) 
)			     

;;privacy for randomly selected cycles (attacks are focused on the flat areas of the logistic function)
(defn attack-per-cycle [results attack-cycles-outputfile epsilon attack-count]
(def counterX (atom 0))
		(for [result results]
				    (if  (= (-> result :strategy):a-rpcn) ;;consider the privacy for the attack strategy a-rpcn
				        (let [  index (-> result :unknown-index)
				                error (-> result :relative-error)
				                count-value (if (<= error epsilon)
                                         (swap! counterX inc)
		                                     (+ @counterX 0) 
                                      )   
				             ]
             ;; (println error)
		              (save-data attack-cycles-outputfile (double (/ count-value attack-count)) )
		             )
				    
				        )
				) 
)
;;Getting the average accuracy for the starting/ending flat areas of the cycles
(defn accuracy-for-flat-areas [accuracy-details full-cycle flat-record-length record-count accuracy-period-file]
 (def acc-sum (atom 0)) 
 (def cycles (int (/ record-count full-cycle ))) 
   (loop [i 0]
     (when (<= i (- cycles 1)) ;;need to change with the record-count (cycles /cycles -1)
        (let [ ;; index (+ (* i full-cycle) flat-record-length);; starting
                index  (* (+ i 1) full-cycle)  ;;ending
                accuracy (get-in accuracy-details [index :value])
              ]
           (reset! acc-sum (+ @acc-sum accuracy))
           
           ;;(println index "Index")
         ;; (println accuracy "accuracy")
         ;;  (println @acc-sum "acc-sum")
         )
        (recur (+ i 1))
      )
    )
   (save-data-append-newline accuracy-period-file (/ @acc-sum  cycles)) ;;(/ @acc-sum (+ cycles 1)) ;;need to change with the record-count
)

;;retriving the accuracy of the attacked records ;;Not a fair method since accuracy readings for the attacked records means the accuracy upto that record.
;;so accuracy cannot be compared with the privacy. Moved to accuracy-for-flat-areas
#_(defn accuracy-of-attacked-records [accuracy-details privacy-results accuracy-for-attcked-records-file]
    (for [result privacy-results]
       (if  (= (-> result :strategy):a-rpcn)
         (let [ record-index (-> result :unknown-index)
                attacked-acc (get-in accuracy-details [record-index :value])
              ;;  attacked-acc ()
             
               ]
           (save-data-append-newline accuracy-for-attcked-records-file attacked-acc) 
           )
         )
      )
  
  
)