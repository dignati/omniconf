;; Copyright 2016 Grammarly, Inc.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0

(ns omniconf.core
  "Omniconf is an exhaustive configuration tool."
  (:refer-clojure :exclude [get set])
  (:require [clojure.core :as clj]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint cl-format]]
            [clojure.string :as str])
  (:import java.io.File))

;; Plumbing

(def ^:private config-scheme
  "Stores configuration description of the program."
  (atom nil))

(def ^:private config-values
  "Stores current configuration values."
  (atom nil))

(def ^:private logging-fn
  "Function that is called to print debugging information and errors."
  (atom println))

(defn set-logging-fn
  "Change `println` to a custom logging function that Omniconf will use."
  [fn]
  (reset! logging-fn fn))

(defn- quit-or-rethrow
  "If `quit-on-error` is true, log the exception and exit 1 the application,
  otherwise rethrow it."
  [e quit-on-error]
  (if quit-on-error
    (do (binding [*out* *err*] (@logging-fn "ERROR:" (.getMessage e)))
        (System/exit 1))
    (throw e)))

(defn fail
  "Throws an exception with a message specified by `fmt` and `args`."
  [fmt & args]
  (throw (ex-info (apply format fmt args) {})))

;; Parsing

(defn parse-edn
  "Calls `clojure.edn/read-string` on a string."
  [s]
  (edn/read-string s))

(defn parse-number
  "Parses string as a Long."
  [s]
  (Long/parseLong s))

(defn parse-boolean
  "Parses string as a Boolean."
  [s]
  (not (#{nil "" "0" "false"} s)))

(defn parse-filename
  "Parses string as a relative filename."
  [s]
  (io/file s))

(def ^:private default-types
  "A map of standard types to their parsers and type checkers. A checker is just
  one internal kind of verifier."
  {:string {:parser identity, :checker string?}
   :keyword {:parser keyword, :checker keyword?}
   :number {:parser parse-number, :checker number?}
   :boolean {:parser parse-boolean, :checker (partial instance? Boolean)}
   :file {:parser parse-filename, :checker (partial instance? File)}
   :directory {:parser parse-filename, :checker #(and (instance? File %)
                                                      (or (not (.exists %))
                                                          (.isDirectory %)))}
   :edn {:parser parse-edn, :checker (constantly true)}})

(defn- parse
  "Given an option spec and the string value, tries to parse that value."
  [spec value-str]
  (let [parser (if (:nested spec)
                 #(let [value (edn/read-string %)]
                    (if (map? value)
                      value
                      (fail "%s : Value of nested option should be a map, instead '%s'."
                            (:name spec) value)))
                 (or (:parser spec)
                     (get-in default-types [(:type spec) :parser])
                     identity))]
    (try (parser value-str)
         (catch clojure.lang.ExceptionInfo e (throw e))
         (catch Exception e (fail "%s : Couldn't parse value '%s'."
                                  (:name spec) value-str)))))

(defn get
  "Get the value from the current configuration given the path in nested
  structure specified by `ks`. Path can be provided as a single sequence, or as
  a variable number of keywords."
  [& ks]
  (let [ks (if (sequential? (first ks)) (first ks) ks)
        value (clj/get-in @config-values ks)]
    (if (delay? value)
      (let [calc-value (force value)]
        (swap! config-values assoc-in ks calc-value)
        calc-value)
      value)))

(defn set
  "Set the `value` for the `ks` path in the current configuration. Path can be
  provided as a single sequence, or as a variable number of keywords. If value
  is a string, call the respective parser on it before setting."
  {:forms '([& ks value] [ks-vec value])}
  [& args]
  (let [[ks value] (if (sequential? (first args))
                     ((juxt first second) args)
                     ((juxt butlast last) args))
        special-action (cond
                         (:merge (meta value)) merge
                         (:concat (meta value)) #(seq (concat %1 %2)))
        dt (:delayed-transform (get-in @config-scheme ks))
        new-value (if special-action
                    (special-action (get ks) value)
                    value)]
    (swap! config-values assoc-in ks (if dt
                                       (delay (dt new-value))
                                       new-value))))

(defmacro with-options
  "Binds given symbols to respective configuration parameters and executes
  `body` in that context. Works only for top-level parameters."
  [bindings & body]
  `(let [~@(mapcat (fn [sym] [sym `(get ~(keyword sym))]) bindings)]
     ~@body))

(defn- fill-default-values
  "When called after a configuration schema is defined, sets the values for
  options that have defaults."
  []
  (let [walk (fn walk [prefix coll]
               (doseq [[kw-name spec] coll]
                 (when-some [default (:default spec)]
                   (apply set (conj prefix kw-name default)))
                 (when-let [nested (:nested spec)]
                   (walk (conj prefix kw-name) nested))))]
    (walk [] @config-scheme)))

(def env-char-replacements
  "Valid characters in a keyword and their replacements in environment variables.
   Check out https://clojure.org/reference/reader for a list of allowed characters."
  {\/ "__SLASH__"
   \. "__DOT__"
   \* "__STAR__"
   \+ "__PLUS__"
   \! "__EXCL__"
   \- "__"
   \_ "__BAR__"
   \' "__TICK__"
   \? "__Q__"})

(defn munge-env-fragment 
  "Takes a keyword and replaces all special characters to make it a valid
   environment variable fragment."
  [fragment]
  (->> (name fragment)
       (map (fn [c] (get env-char-replacements c c)))
       (apply str)
       (.toUpperCase)))

(defn define
  "Declare the configuration that the program supports. `scheme` is a map of
  keyword names to specs.

  Example:

  (define {:boolean-option {:description \"can be either true or nil\"}
           :string-option  {:type :string
                            :description \"this option's value is taken as is\"}
           :integer-option {:type :number
                            :required true
                            :description \"parsed as integer, must be present\"}}

  Supported attributes:

  :description - string to describe the option in the help message.
  :type - one of #{:string :keyword :number :boolean :edn :file :directory}.
  :parser - 1-arity fn to be called on a string given by CMD args or ENV.
            Unnecessary if :type is specified.
  :required - boolean value whether the option must have a value;
              or 0-arity fn, if it returns true, the option must have a value.
  :one-of - a set of accepted values for the given option.
  :verifier - fn on key and val, should throw an exception if option is invalid.
  :secret - if true, value will not be printed during verification.
  :nested - allows to create hierarchies of options."
  [scheme]
  (reset! config-values (sorted-map))

  ;; Recursively update scheme.
  (letfn [(walk [prefix coll]
            (->> coll
                 (map (fn [[kw-name spec]]
                        [kw-name (-> spec
                                     (assoc :name kw-name)
                                     (update-in [:env-name]
                                                #(->> (conj prefix (or % kw-name))
                                                      (map munge-env-fragment)
                                                      (str/join "__")))
                                     (update-in [:opt-name]
                                                #(->> (conj prefix (or % kw-name))
                                                      (map name)
                                                      (str/join ".")
                                                      (str "--")))
                                     (update-in [:prop-name]
                                                #(->> (conj prefix (or % kw-name))
                                                      (map name)
                                                      (str/join ".")))
                                     (update-in [:nested]
                                                #(when % (walk (conj prefix kw-name) %))))]))
                 (into (sorted-map))))]
    (reset! config-scheme (walk [] scheme)))

  (fill-default-values))

(defn- flatten-and-transpose-scheme
  "Returns a flat hashmap from scheme where nested specs are in the top level,
  and keys are either string values from `:env-name`, `:opt-name`, or keyword
  paths. Inside specs `:name` is transformed into a vector of keywords - path to
  that option. Source is `:env`, `:cmd`, `:prop`, or `:kw`."
  [source scheme]
  (letfn [(fats [prefix scheme]
            (->> scheme
                 (mapcat (fn [[_ spec]]
                           (let [spec (update-in spec [:name] #(conj prefix %))
                                 key ((case source
                                        :env :env-name
                                        :cmd :opt-name
                                        :prop :prop-name
                                        :kw :name) spec)]
                             (if-let [nested (:nested spec)]
                               (cons [key spec]
                                     (fats (:name spec) nested))
                               [[key spec]]))))
                 (into {})))]
    (fats [] scheme)))

(defn populate-from-env
  "Fill configuration from environment variables. This function must be called
  only after `define`. If `quit-on-error` is true, immediately quit when program
  occurs."
  ([] (populate-from-env false))
  ([quit-on-error]
   (try
     (doseq [[env-name spec] (flatten-and-transpose-scheme :env @config-scheme)]
       (when-let [value (clj/get (System/getenv) env-name)]
         (set (:name spec) (parse spec value))))
     (catch clojure.lang.ExceptionInfo e (quit-or-rethrow e quit-on-error)))))

(defn print-cli-help
  "Prints a help message describing all supported command-line arguments."
  []
  (cl-format true "~:[Standalone script~;~:*~A~]~:[.~; - ~:*~A~]~%~%"
             (get-in @config-scheme [:help :help-name])
             (get-in @config-scheme [:help :help-description]))
  (let [options (->> (flatten-and-transpose-scheme :cmd @config-scheme)
                     vals
                     (remove :nested)
                     (sort-by :opt-name))
        name-width (apply max (map #(count (:opt-name %)) options))]
    (doseq [{:keys [opt-name description required default secret]} options]
      (cl-format true (format "~%dA - ~A. ~A~:[~;Default: ~:*~A~]~%%" name-width)
                 opt-name description
                 (cond (fn? required) "Conditionally required. "
                       required "Required. "
                       :else "")
                 (when default
                   (if secret "<SECRET>" default))))))

(defn populate-from-cmd
  "Fill configuration from command-line arguments."
  ([cmd-args] (populate-from-cmd cmd-args false))
  ([cmd-args quit-on-error]
   (let [grouped-opts
         (loop [[c & r] (conj (vec cmd-args) ::end), curr-opt nil, result []]
           (cond (= c ::end) (if curr-opt
                               (conj result [curr-opt true])
                               result)
                 (.startsWith c "--") (recur r c (if curr-opt
                                                   (conj result [curr-opt true])
                                                   result))
                 curr-opt (recur r nil (conj result [curr-opt c]))
                 :else (fail "Malformed command-line arguments, key expected, '%s' found."
                             c)))]
     (when (clj/get (into {} grouped-opts) "--help")
       (print-cli-help)
       (System/exit 0))

     (try (let [transposed-scheme (flatten-and-transpose-scheme :cmd @config-scheme)]
            (doseq [[k v] grouped-opts]
              (if-let [spec (clj/get transposed-scheme k)]
                (set (:name spec) (parse spec v))
                (@logging-fn "WARNING: Unrecognized option:" k))))
          (catch clojure.lang.ExceptionInfo e (quit-or-rethrow e quit-on-error))))))

(defn populate-from-file
  "Fill configuration from an edn file."
  ([edn-file] (populate-from-file edn-file false))
  ([edn-file quit-on-error]
   (try (with-open [in (java.io.PushbackReader. (io/reader edn-file))]
          (letfn [(walk [prefix tree]
                    (doseq [[key value] tree]
                      (let [path (conj prefix key)
                            spec (get-in @config-scheme path)]
                        (if (:nested spec)
                          (walk path value)
                          (set path (if (string? value)
                                      (parse spec value)
                                      value))))))]
            (walk [] (edn/read in))))
        (catch clojure.lang.ExceptionInfo e (quit-or-rethrow e quit-on-error)))))

(defn populate-from-properties
  "Fill configuration from Java properties."
  ([] (populate-from-properties false))
  ([quit-on-error]
   (try
     (doseq [[prop-name spec] (flatten-and-transpose-scheme :prop @config-scheme)]
       (when-let [value (System/getProperty prop-name)]
         (set (:name spec) (parse spec value))))
     (catch clojure.lang.ExceptionInfo e (quit-or-rethrow e quit-on-error)))))

(defn report-configuration
  "Prints the current configuration state to `*out*`. Hide options marked as
  `:secret`."
  []
  (@logging-fn "Omniconf configuration:\n"
   (with-out-str
     (pprint
      (reduce (fn [values-map [val-path val-spec]]
                (if (and (:secret val-spec) (get-in values-map val-path))
                  (assoc-in values-map val-path '<SECRET>)
                  values-map))
              @config-values
              (flatten-and-transpose-scheme :kw @config-scheme))))))

(defn verify
  "Checks if all the required options are provided, if all values are in range,
  and prints the configuration. If `:quit-on-error` is set, script will exit if
  configuration is incorrect. If `:silent` is true, don't print the
  configuration state."
  [& {:keys [quit-on-error silent]}]
  (swap! config-scheme dissoc :help) ;; Not needed anymore.
  (try (doseq [[kw-name spec] (flatten-and-transpose-scheme :kw @config-scheme)]
         (let [value (get-in @config-values kw-name)]
           ;; Not using `cfg/get` above to avoid forcing delays too early.
           (when-let [r (:required spec)]
             (when (and (if (fn? r) (r) r)
                        (nil? value))
               (fail "%s : Value must be provided." kw-name)))
           (when-let [one-of (:one-of spec)]
             (when (= ::not-found (clj/get (clj/set one-of) value ::not-found))
               (fail "%s : Value is %s, but must be one of %s"
                     kw-name value one-of)))
           (when (some? value)
             (when-let [type (:type spec)]
               (when-not (clj/get default-types type)
                 (fail "%s : Unknown type %s" kw-name type))
               (when-not (or (:delayed-transform spec)
                             ((get-in default-types [type :checker]) value))
                 (fail "%s : Value must have type %s, but is %s"
                       kw-name type value)))
             (when-let [verifier (:verifier spec)]
               (verifier kw-name value)))))
       (catch clojure.lang.ExceptionInfo e (quit-or-rethrow e quit-on-error)))
  (when-not silent (report-configuration)))

(defn verify-file-exists
  "Check if file or directory denoted by `file` exists, raise error otherwise."
  [key file]
  (when-not (.exists file)
    (throw (ex-info (format "%s : Path %s does not exist." key file) {}))))

(defn verify-directory-non-empty
  "Check if `dir` contains at least one file. Also checks that `dir` exists."
  [key dir]
  (verify-file-exists key dir)
  (when-not (seq (.list dir))
    (throw (ex-info (format "%s : Directory %s is empty." key dir) {}))))
