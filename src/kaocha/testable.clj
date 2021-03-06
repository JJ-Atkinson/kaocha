(ns kaocha.testable
  (:refer-clojure :exclude [load])
  (:require [clojure.spec.alpha :as s]
            [kaocha.specs :refer [assert-spec]]
            [kaocha.history :as history]
            [kaocha.result :as result]
            [kaocha.plugin :as plugin]
            [clojure.pprint :as pprint]
            [clojure.java.io :as io]
            [kaocha.output :as output]
            [kaocha.classpath :as classpath]
            [clojure.test :as t]
            [kaocha.hierarchy :as hierarchy])
  (:import [clojure.lang Compiler$CompilerException]))

(def ^:dynamic *fail-fast?*
  "Should testing terminate immediately upon failure or error?"
  nil)

(defn add-desc [testable description]
  (assoc testable ::desc
         (str (name (::id testable)) " (" description ")")))

(defn- try-require [n]
  (try
    (require n)
    true
    (catch java.io.FileNotFoundException e
      false)))

(defn try-load-third-party-lib [type]
  (if (qualified-keyword? type)
    (when-not (try-require (symbol (str (namespace type) "." (name type))))
      (try-require (symbol (namespace type))))
    (try-require (symbol (name type)))))

(defn- load-type+validate
  "Try to load a testable type, and validate it both to be a valid generic testable, and a valid instance given the type.

  Implementing a new type means creating a namespace based on type's name, e.g.
  `:my.new/testable` should be in `my.new` or `my.new.testable`.

  This file should implement the multimethods `-load` and `-run`, as well as a
  spec for this type of testable."
  [testable]
  (assert-spec :kaocha/testable testable)
  (let [type (::type testable)]
    (try-load-third-party-lib type)
    (assert-spec type testable)))

(defmulti -load
  "Given a testable, load the specified tests, producing a test-plan."
  ::type)

(defmethod -load :default [testable]
  (throw (ex-info (str "No implementation of "
                       `load
                       " for "
                       (pr-str (::type testable)))
                  {:kaocha.error/reason         :kaocha.error/missing-method
                   :kaocha.error/missing-method `load
                   :kaocha/testable             testable})))

(defn load
  "Given a testable, load the specified tests, producing a test-plan.

  Also performs validation, and lazy loading of the testable type's
  implementation."
  [testable]
  (load-type+validate testable)
  (doseq [path (:kaocha/test-paths testable)]
    (when-not (.exists (io/file path))
      (output/warn "In :test-paths, no such file or directory: " path))
    (when-not (::skip-add-classpath? testable)
      (classpath/add-classpath path)))

  (try
    (-load testable)
    (catch Exception t
      (if (hierarchy/suite? testable)
        (assoc testable ::load-error t)
        (throw t)))))

(s/fdef load
  :args (s/cat :testable :kaocha/testable)
  :ret :kaocha.test-plan/testable)

(def ^:dynamic *current-testable* nil)
(def ^:dynamic *test-plan* nil)

(def ^:dynamic *test-location*
  "Can be bound by a test type to override detecting the current line/file from
  the stacktrace in case of failure. The value should be a map with keys `:file`
  and `:line`."
  nil)

(defmulti -run
  "Given a test-plan, perform the tests, returning the test results."
  (fn [testable test-plan]
    (::type testable)))

(defmethod -run :default [testable test-plan]
  (throw (ex-info (str "No implementation of "
                       `run
                       " for "
                       (pr-str (:kaocha.testable/type testable)))
                  {:kaocha.error/reason         :kaocha.error/missing-method
                   :kaocha.error/missing-method `run
                   :kaocha/testable             testable})))

(defn run
  "Given a test-plan, perform the tests, returning the test results.

  Also performs validation, and lazy loading of the testable type's
  implementation."
  [testable test-plan]
  (load-type+validate testable)
  (binding [*current-testable* testable]
    (let [run (plugin/run-hook :kaocha.hooks/wrap-run -run test-plan)
          result (run testable test-plan)]
      (if-let [history history/*history*]
        (assoc result
               ::events
               (filter (fn [event]
                         (= (get-in event [:kaocha/testable ::id])
                            (::id testable)))
                       @history))
        result))))

(s/fdef run
        :args (s/cat :testable :kaocha.test-plan/testable
                     :test-plan :kaocha/test-plan)
        :ret :kaocha.result/testable)

(defn load-testables
  "Load a collection of testables, returning a test-plan collection"
  [testables]
  (loop [result []
         [test & testables] testables]
    (if test
      (let [r (if (or (::skip test) (::load-error test))
                test
                (load test))]
        (if (and *fail-fast?* (:kaocha.test-plan/load-error r))
          (reduce into [[r] result testables]) ;; move failing test to the front
          (recur (conj result r) testables)))
      result)))

(defn run-testable [test test-plan]
  (let [test (plugin/run-hook :kaocha.hooks/pre-test test test-plan)]
    (cond
      (::load-error test)
      (let [error (::load-error test)
            m (if-let [message (::load-error-message test)]
                {:type :error
                 :message message
                 :kaocha/testable test}
                {:type :error
                 :actual (::load-error test)
                 :kaocha/testable test})
            m (cond-> m
                (::load-error-file test)
                (assoc :file (::load-error-file test))

                (::load-error-line test)
                (assoc :line (::load-error-line test))

                (instance? Compiler$CompilerException error)
                (assoc :file (.-source ^Compiler$CompilerException error)
                       :line (.-line ^Compiler$CompilerException error)))]
        (t/do-report (assoc m :type :kaocha/begin-suite))
        (binding [*fail-fast?* false]
          (t/do-report m))
        (t/do-report (assoc m :type :kaocha/end-suite))
        (assoc test
               ::events [m]
               :kaocha.result/count 1
               :kaocha.result/error 1))

      (::skip test)
      test

      (or (:kaocha.testable/pending test)
          (-> test ::meta :kaocha/pending))
      (do
        (let [m {:type :kaocha/pending
                 :file (-> test ::meta :file)
                 :line (-> test ::meta :line)
                 :kaocha/testable test}]
          (t/do-report (assoc m :type :kaocha/begin-test))
          (t/do-report m)
          (t/do-report (assoc m :type :kaocha/end-test))
          (assoc test
                 ::events [m]
                 :kaocha.result/count 1
                 :kaocha.result/pending 1)))

      :else
      (as-> test %
            (run % test-plan)
            (plugin/run-hook :kaocha.hooks/post-test % test-plan)))))

(defn run-testables
  "Run a collection of testables, returning a result collection."
  [testables test-plan]
  (let [load-error? (some ::load-error testables)]
    (loop [result []
           [test & testables] testables]
      (if test
        (let [test (cond-> test
                     (and load-error? (not (::load-error test)))
                     (assoc ::skip true))
              r (run-testable test test-plan)]
          (if (or (and *fail-fast?* (result/failed? r)) (::skip-remaining? r))
            (reduce into result [[r] testables])
            (recur (conj result r) testables)))
        result))))

(defn test-seq [testable]
  (cons testable (mapcat test-seq (remove ::skip (or (:kaocha/tests testable)
                                                     (:kaocha.test-plan/tests testable)
                                                     (:kaocha.result/tests testable))))))

(defn load-error? [testable]
  (boolean (:kaocha.test-plan/load-error testable)))

(defn handle-load-error [testable]
  (when-let [load-error (:kaocha.test-plan/load-error testable)]
    (t/do-report {:type                    :error
                  :message                 "Failed to load namespace."
                  :expected                nil
                  :actual                  load-error
                  :kaocha.result/exception load-error})
    (assoc testable :kaocha.result/error 1)))
