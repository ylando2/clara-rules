(ns clara.tools.test-inspect
  (:require [clara.tools.inspect :refer :all]
            [clara.rules :refer :all]
            [clara.rules.testfacts :refer :all]
            [clara.rules.dsl :as dsl]
            [clara.rules.engine :as eng]
            [clara.rules.accumulators :as acc]
            [clojure.test :refer :all]
            schema.test)
  (:import [clara.rules.testfacts Temperature WindSpeed Cold
            ColdAndWindy LousyWeather First Second Third Fourth]))

(use-fixtures :once schema.test/validate-schemas)

(deftest test-simple-inspect

  ;; Create some rules and a session for our test.
  (let [cold-query (assoc (dsl/parse-query [] [[Temperature (< temperature 20) (= ?t temperature)]])
                     :name "Beep")

        cold-rule (dsl/parse-rule [[Temperature (< temperature 20) (= ?t temperature)]]
                                  (println ?t "is cold!") )

        hot-rule (dsl/parse-rule [[Temperature (> temperature 80) (= ?t temperature)]]
                                 (println ?t "is hot!") )

        session (-> (mk-session [cold-query cold-rule hot-rule])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 90 "MCI")))

        rule-dump (inspect session)]

    ;; Retrieve the tokens matching the cold query. This test validates
    ;; the tokens contain the expected matching conditions by retrieving
    ;; them directly from the query in question.
    (is (= [(map->Explanation {:matches [[(->Temperature 15 "MCI")
                                          (first (:lhs cold-query))]],

                               :bindings {:?t 15}})

            (map->Explanation {:matches [[(->Temperature 10 "MCI")
                                          (first (:lhs cold-query))]],
                               :bindings {:?t 10}})]

           (get-in rule-dump [:query-matches cold-query])))

    ;; Retrieve tokens matching the hot rule.
    (is (= [(map->Explanation {:matches [[(->Temperature 90 "MCI")
                                          (first (:lhs hot-rule))]],
                               :bindings {:?t 90}})]

           (get-in rule-dump [:rule-matches hot-rule])))

    ;; Ensure the first condition in the rule matches the expected facts.
    (is (= [(->Temperature 15 "MCI") (->Temperature 10 "MCI")]
           (get-in rule-dump [:condition-matches  (first (:lhs cold-rule))])))))


(deftest test-accum-inspect
  (let [lowest-temp (acc/min :temperature :returns-fact true)
        coldest-query (dsl/parse-query [] [[?t <- lowest-temp from [Temperature]]])

        session (-> (mk-session [coldest-query])
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 80 "MCI")))]

    (let [accum-condition (-> coldest-query :lhs  first (select-keys [:accumulator :from]))
          query-explanations (-> (inspect session) (:query-matches) (get coldest-query) )]

      (is (= [(map->Explanation {:matches [[(->Temperature 10 "MCI") accum-condition]]
                                 :bindings {:?t (->Temperature 10 "MCI")}})]
             query-explanations)))))


(deftest test-accum-join-inspect
  (let [lowest-temp (acc/min :temperature :returns-fact true)

        ;; Get the coldest temperature at MCI that is warmer than the temperature in STL.
        colder-query (dsl/parse-query [] [[Temperature (= "STL" location) (= ?stl-temperature temperature)]
                                          [?t <- lowest-temp from [Temperature (> temperature ?stl-temperature)
                                                                               (= "MCI location")]]])

        session (-> (mk-session [colder-query] :cache false)
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 20 "STL"))
                    (insert (->Temperature 80 "MCI"))
                    (insert (->Temperature 25 "MCI")))]

    (let [matches (-> (inspect session) (:query-matches) (get colder-query) first :matches)]
      (doseq [[fact node-data] matches]

        ;; The accumulator condition should have two constraints.
        (when (:accumulator node-data)
          (is (= 2 (-> node-data :from :constraints (count)))))))))

(deftest test-extract-test-inspect
  (let [distinct-temps-query (dsl/parse-query [] [[Temperature (< temperature 20)
                                                               (= ?t1 temperature)]

                                                  [Temperature (< temperature 20)
                                                               (= ?t2 temperature)
                                                               (< ?t1 temperature)]])

        session  (-> (mk-session [distinct-temps-query] :cache false)
                     (insert (->Temperature 15 "MCI"))
                     (insert (->Temperature 10 "MCI"))
                     (insert (->Temperature 80 "MCI")))]

    ;; Ensure that no returned contraint includes a generated variable name.
    (doseq [{:keys [matches bindings]} (-> (inspect session) :query-matches (get distinct-temps-query))
            [fact {:keys [ type constraints]}] matches
            term (flatten constraints)]
      (is (not (and (symbol? term)
                    (.startsWith (name term) "?__gen" )))))))
