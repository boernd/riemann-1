(ns robinhood.consumers.processor
  (:require (riemann common
                     config
                     [streams :refer :all]
                     [test :refer [tap io inject! deftest]])
            [robinhood.notifiers.common :refer [prep-output-streams]]
            [robinhood.notifiers.log :refer [log-info]]
            [robinhood.rules.common :refer [check-event-tags filter-events]]
            [robinhood.rules.common :refer [aggregate-by]
             :rename {aggregate-by aggr}]))

(defn- prep-streams
  "All rule functions extract thresholds and modifiers (:modifiers)
   from rules. The prepper simply generates a partial function that
   calls the rule functions with the rule hash."
  [conditions stream-list]
  (map (fn [stream-fn]
         (partial stream-fn conditions)) stream-list))

(defn- thread-streams
  "To thread streams
   1. Get the prepped partial stream from the stream prepper
   2. Compose them"
  [conditions stream-list]
  (apply comp (prep-streams conditions stream-list)))

(defn process-single-metric-rule
  "For every rule create the partial threaded streams and then attach
   the notifier function at the end to complete them. We start with
   the last stream first and thread back to first."
  ([rule]
     (process-single-metric-rule rule (prep-output-streams rule)))
  ([rule notifier-stream]
     (let [stream-list (conj (:thread-through rule)
                             filter-events)
           threaded-streams (thread-streams rule (reverse stream-list))]
       (threaded-streams notifier-stream))))

(defn- transform-events [rule events]
  "For a multi-metric rule, this transforms the incoming events into a
   single event, iff all of the events are non-nil, and none of the
   events have expired."
  (let [modifiers (:modifiers rule)]
    (when (and (every? true? (map boolean events))
               (every? false? (map expired? events)))
      (if-let [transformed-metric (apply (:transform rule)
                                         (map :metric events))]
        (riemann.common/event
         {:metric transformed-metric
          :service (:description modifiers)
          :description (:description modifiers)
          :host (:host (first events))
          :roles (:roles (first events))
          :tags (:tags (first events))
          :ttl (get modifiers :ttl
                    (riemann.config/rh_config "default_ttl"))})))))

(defn- make-projector [multi-rule]
  "For each set of multi metric rule, this creates a projector that
   creates filters from each of the underlying single metric
   rules. The output from each of these filters is sent to project*"
  (let [sub-rules (:filters multi-rule)
        threaded-top-streams (thread-streams
                              multi-rule
                              (reverse (:thread-through multi-rule)))
        notifier-stream (prep-output-streams multi-rule)]
    (aggr multi-rule
          (project*
           (vec (map (fn [sub-rule]
                       (partial check-event-tags sub-rule))
                     sub-rules))
           (smap (partial transform-events multi-rule)
                 (threaded-top-streams notifier-stream))))))

(defn process-multi-metric-rule
  "For every rule create the partial threaded streams and then attach
   the notifier function at the end to complete them. We start with
   the last stream first and thread back to first."
  ([multi-rule]
     (let [sub-rules (:filters multi-rule)
           projector (make-projector multi-rule)]
       (apply sdo
              (map (fn [sub-rule]
                     (process-single-metric-rule sub-rule projector))
                   sub-rules)))))

(let [rule-processors {:single-metric-rules process-single-metric-rule
                       :multi-metric-rules process-multi-metric-rule}
      stream-generator (fn [rule-type rule]
                         (let [processor (rule-type rule-processors)]
                           (processor (assoc rule
                                        :rule-id
                                        (str (java.util.UUID/randomUUID))))))]
  (defn process-rules [[rule-type rules]]
    (flatten
     (map (partial stream-generator rule-type) rules))))

(defn extract-single-metric-name
  "For every rule extract the metric name that we care about"
  ([rule]
     (:metric-name rule)))

(defn extract-multi-metric-names
  "For every rule extract the metric names that we care about"
  ([rule]
     (map extract-single-metric-name (:filters rule))))

(let [rule-processors {:single-metric-rules extract-single-metric-name
                       :multi-metric-rules extract-multi-metric-names}]
  (defn get-interesting-metrics [[rule-type rules]]
    (map (fn [rule]
           ((rule-type rule-processors) rule)) rules)))

