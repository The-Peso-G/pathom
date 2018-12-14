(ns com.wsscode.pathom.gen-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [is are testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.clojure-test :as test]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as props]
            [com.wsscode.pathom.gen :as sgen]
            [edn-query-language.core :as eql]
            [fulcro.client.primitives :as fp]
            [nubank.workspaces.core :refer [deftest]]))

(def gen-env
  {::sgen/silent?  true
   ::sgen/settings {:id               {::sgen/gen (s/gen string?)}
                    :name             {::sgen/gen (s/gen string?)}
                    :title            {::sgen/gen (s/gen string?)}
                    :foo              {::sgen/gen (s/gen string?)}
                    :bar              {::sgen/gen (s/gen string?)}
                    :other            {::sgen/gen (s/gen string?)}
                    :price            {::sgen/gen (s/gen string?)}
                    :namespaced/value {::sgen/gen (s/gen string?)}
                    'mutation         {::sgen/fn (fn [env]
                                                   {:mutation "response"})}}})

(s/def ::coll (s/coll-of int?))
(s/def ::not-coll int?)
(s/def ::n1 #{2})
(s/def ::n2 #{1 3})
(s/def ::fixed-number #{42})
(s/def ::fixed-str #{"bla"})
(s/def ::some-id uuid?)

(fp/defsc Component [_ _ _]
  {:ident (fn [] [:fixed "here"])
   :query [::fixed-number ::fixed-str]}
  (identity nil))

(fp/defsc ComponentInit [_ _ _]
  {:initial-state (fn [x]
                    (cond-> {:ui/some-state "foo"
                             ::fixed-number 33}
                      x (assoc ::fixed-number x)))
   :ident         (fn [] [:fixed "here"])
   :query         [::fixed-number ::fixed-str :ui/some-state]}
  (identity nil))

(deftest test-coll-spec?
  (is (true? (sgen/coll-spec? ::coll)))
  (is (false? (sgen/coll-spec? ::not-coll)))
  (is (false? (sgen/coll-spec? ::invalid))))

(deftest test-query->props
  (is (= (sgen/query->props gen-env [::fixed-number ::fixed-str ::undefined])
         {::fixed-number 42 ::fixed-str "bla" ::undefined nil}))

  (is (= (sgen/query->props gen-env [::fixed-number ::fixed-str
                                     {:ui/join [::fixed-number]}])
         {::fixed-number 42 ::fixed-str "bla"}))

  (is (= (sgen/query->props {::sgen/settings {::number-list {::sgen/coll 10}}}
           [{::number-list [::fixed-number]}])
         {::number-list (repeat 10 {::fixed-number 42})}))

  (is (= (sgen/query->props (sgen/set-gen {} ::fixed-number (s/gen #{43}))
           [::fixed-number])
         {::fixed-number 43}))

  (is (= (sgen/query->props [[::fixed-number '_]])
         {[::fixed-number '_] 42}))

  (is (= (sgen/query->props [{[::some-id 123]
                              [::some-id ::fixed-str]}])
         {[:com.wsscode.pathom.gen-test/some-id 123]
          {:com.wsscode.pathom.gen-test/some-id   123
           :com.wsscode.pathom.gen-test/fixed-str "bla"}}))

  (is (= (sgen/query->props gen-env ['(mutation {:foo "bar"})])
         {'mutation {:mutation "response"}})))

(defn generate-props []
  (let [props (->> (keys (::sgen/settings gen-env))
                   (filter keyword?))]
    (props/for-all [query (eql/make-gen
                            {::eql/gen-property
                             (fn [_] (gen/elements props))

                             ::eql/gen-ident-key
                             (fn [_] (gen/elements props))

                             ::eql/gen-union-key
                             (fn [_] (gen/elements props))

                             ::eql/gen-params
                             (fn [_] (gen/return {}))}

                            ::eql/gen-query)]
      (sgen/query->props gen-env query))))

(deftest test-comp->props
  (is (= (sgen/comp->props Component)
         {::fixed-number 42 ::fixed-str "bla"}))

  (is (= (sgen/comp->props ComponentInit)
         {::fixed-number 33 ::fixed-str "bla"
          :ui/some-state "foo"}))

  (is (= (sgen/comp->props {::sgen/initialize false} ComponentInit)
         {::fixed-number 42 ::fixed-str "bla"}))

  (is (= (sgen/comp->props {::sgen/initialize #(dissoc % ::fixed-number)} ComponentInit)
         {::fixed-number 42 ::fixed-str "bla"
          :ui/some-state "foo"})))

(deftest test-comp->db
  (is (= (sgen/comp->db Component)
         {::fixed-number 42 ::fixed-str "bla"})))

(defn generate-response
  ([query] (generate-response {} query))
  ([env query]
   (gen/generate (sgen/query-props-generator env query))))

(deftest test-query-data-generator
  (is (= (generate-response gen-env [::fixed-number ::fixed-str ::undefined])
         {::fixed-number 42 ::fixed-str "bla" ::undefined nil}))

  (is (= (generate-response gen-env [::fixed-number ::fixed-str
                                              {:simple-join [::fixed-str]}])
         {::fixed-number 42,
          ::fixed-str    "bla",
          :simple-join   {::fixed-str "bla"}}))

  (is (= (generate-response gen-env [::fixed-number ::fixed-str
                                              {:ui/join [::fixed-number]}])
         {::fixed-number 42 ::fixed-str "bla"}))

  (is (= (generate-response {::sgen/settings {::number-list {::sgen/coll 10}}}
           [{::number-list [::fixed-number]}])
         {::number-list (repeat 10 {::fixed-number 42})}))

  (is (= (generate-response {::sgen/settings {::number-list {::sgen/coll              10
                                                                      ::sgen/distinct ::fixed-number}}}
           [{::number-list [::fixed-number]}])
         {::number-list [{::fixed-number 42}]}))

  (is (= (generate-response {::sgen/settings {::fixed-number {::sgen/gen (s/gen #{43})}}}
           [::fixed-number])
         {::fixed-number 43}))

  (is (= (generate-response [[::fixed-number '_]])
         {[::fixed-number '_] 42}))

  (is (= (generate-response [{[::some-id 123]
                                       [::some-id ::fixed-str]}])
         {[:com.wsscode.pathom.gen-test/some-id 123]
          {:com.wsscode.pathom.gen-test/some-id   123
           :com.wsscode.pathom.gen-test/fixed-str "bla"}}))

  (is (= (generate-response
           {::sgen/transform-generator
            (fn [x] (gen/fmap inc x))}
           [::fixed-number])
         {::fixed-number 43}))

  (testing "mutations"
    (is (= (generate-response ['(foo {:bar "baz"})])
           {'foo {}}))

    (testing "remap tempid"
      (let [tempid (fp/tempid)
            uuid   (sgen/gen-uuid)]
        (with-redefs [sgen/gen-uuid (fn [] uuid)]
          (is (= (generate-response [(list 'foo {:bar tempid})])
                 {'foo {::fp/tempids {tempid uuid}}}))))

      (let [tempid (fp/tempid)
            uuid   (sgen/gen-uuid)]
        (with-redefs [sgen/gen-uuid (fn [] uuid)]
          (is (= (generate-response {::sgen/remap-tempids? false} [(list 'foo {:bar tempid})])
                 {'foo {}})))))

    (testing "returning"
      (is (= (generate-response [{'(foo {:bar "baz"})
                                  [::fixed-number]}])
             {'foo {::fixed-number 42}})))

    (testing "remap tempid + returning"
      (let [tempid (fp/tempid)
            uuid   (sgen/gen-uuid)]
        (with-redefs [sgen/gen-uuid (fn [] uuid)]
          (is (= (generate-response [{(list 'foo {:bar tempid})
                                      [::fixed-number]}])
                 {'foo {::fixed-number 42
                        ::fp/tempids   {tempid uuid}}})))))

    (testing "mutation override"
      (is (= (generate-response {::sgen/settings {'foo {::sgen/mutate (fn [env p]
                                                                        (assoc p :add 2))}}}
               ['(foo {:id 123})])
             {'foo {:id 123 :add 2}}))

      (is (= (generate-response {::sgen/settings {'foo {::sgen/mutate
                                                        (fn [env p]
                                                          (merge (sgen/gen-query-join-sample env)
                                                                 (assoc p :add 2)))}}}
               [{'(foo {:id 123})
                 [::fixed-number]}])
             {'foo {:id 123 :add 2 ::fixed-number 42}}))))

  (testing "meta settings"
    (is (= (generate-response {}
             (with-meta [::fixed-number]
               {::sgen/settings
                {::fixed-number {::sgen/gen (s/gen #{420})}}}))
           {::fixed-number 420}))

    (is (= (generate-response {}
             (with-meta [::fixed-number]
               {::sgen/fmap #(update % ::fixed-number inc)}))
           {::fixed-number 43}))

    (is (= (generate-response {}
             (with-meta [::n1 ::n2]
               {::sgen/such-that (fn [{::keys [n1 n2]}]
                                   (> n1 n2))}))
           {::n1 2 ::n2 1}))))

(comment
  (generate-response [{'(foo {:bar "baz"})
                       [::fixed-number]}]))

(deftest test-comp-data-generator
  (is (= (gen/generate (sgen/comp-props-generator {} Component))
         {::fixed-number 42 ::fixed-str "bla"}))

  (is (= (gen/generate (sgen/comp-props-generator {} ComponentInit))
         {::fixed-number 33 ::fixed-str "bla"
          :ui/some-state "foo"}))

  (is (= (gen/generate (sgen/comp-props-generator {::sgen/initialize false} ComponentInit))
         {::fixed-number 42 ::fixed-str "bla"}))

  (is (= (gen/generate (sgen/comp-props-generator {::sgen/initialize #(dissoc % ::fixed-number)} ComponentInit))
         {::fixed-number 42 ::fixed-str "bla"
          :ui/some-state "foo"})))

#_(test/defspec generate-props-test {:max-size 18 :num-tests 100} (generate-props))
