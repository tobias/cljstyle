(ns cljstyle.config
  "Configuration is provided by a map of keyword settings to values.

  Config may be provided by a Clojure file named `.cljstyle`. These files files
  may be sprinkled about the file tree; each file applies configuration to the
  subtree rooted in the directory the file resides in, with deeper files
  merging and overriding their parents."
  (:require
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s])
  (:import
    java.io.File
    java.nio.file.FileSystems))


;; ## Specs

(defn- pattern?
  "True if the value if a regular expression pattern."
  [v]
  (instance? java.util.regex.Pattern v))


;; Formatting Rules
(s/def ::indentation? boolean?)
(s/def ::list-indent-size nat-int?)
(s/def ::line-break-vars? boolean?)
(s/def ::line-break-functions? boolean?)
(s/def ::reformat-types? boolean?)
(s/def ::remove-surrounding-whitespace? boolean?)
(s/def ::remove-trailing-whitespace? boolean?)
(s/def ::insert-missing-whitespace? boolean?)
(s/def ::remove-consecutive-blank-lines? boolean?)
(s/def ::max-consecutive-blank-lines nat-int?)
(s/def ::insert-padding-lines? boolean?)
(s/def ::padding-lines nat-int?)
(s/def ::rewrite-namespaces? boolean?)
(s/def ::single-import-break-width nat-int?)
(s/def ::require-eof-newline? boolean?)


;; Indentation Rules
(s/def ::indent-key
  (s/or :symbol symbol?
        :pattern pattern?))


(s/def ::indenter
  (s/cat :type #{:inner :block :stair}
         :args (s/+ nat-int?)))


(s/def ::indent-rule
  (s/coll-of ::indenter :kind vector?))


(s/def ::indents
  (s/map-of ::indent-key ::indent-rule))


;; File Behavior
(s/def ::file-pattern pattern?)

(s/def ::file-ignore-rule (s/or :string string? :pattern pattern?))
(s/def ::file-ignore (s/coll-of ::file-ignore-rule :kind set?))


;; Config Map
(s/def ::settings
  (s/keys :opt-un [::indentation?
                   ::list-indent-size
                   ::indents
                   ::line-break-vars?
                   ::line-break-functions?
                   ::reformat-types?
                   ::remove-surrounding-whitespace?
                   ::remove-trailing-whitespace?
                   ::insert-missing-whitespace?
                   ::remove-consecutive-blank-lines?
                   ::max-consecutive-blank-lines
                   ::insert-padding-lines?
                   ::padding-lines
                   ::rewrite-namespaces?
                   ::single-import-break-width
                   ::require-eof-newline?
                   ::file-pattern
                   ::file-ignore]))



;; ## Defaults

(def default-indents
  "Default indentation rules included with the library."
  (read-string (slurp (io/resource "cljstyle/indents.clj"))))


(def default-config
  {:indentation? true
   :list-indent-size 2
   :indents default-indents
   :line-break-vars? true
   :line-break-functions? true
   :reformat-types? true
   :remove-surrounding-whitespace? true
   :remove-trailing-whitespace? true
   :insert-missing-whitespace? true
   :remove-consecutive-blank-lines? true
   :max-consecutive-blank-lines 2
   :insert-padding-lines? true
   :padding-lines 2
   :rewrite-namespaces? true
   :single-import-break-width 30
   :require-eof-newline? true
   :file-pattern #"\.clj[csx]?$"
   :file-ignore #{}})



;; ## Utilities

(defn source-paths
  "Return the sequence of paths the configuration map was merged from."
  [config]
  (::paths (meta config)))


(defn merge-settings
  "Merge configuration maps together."
  ([] nil)
  ([a] a)
  ([a b]
   (letfn [(merge-values
             [x y]
             (cond
               (:replace (meta y)) y
               (:displace (meta x)) y
               (sequential? x) (into x y)
               (set? x) (into x y)
               (map? x) (merge x y)
               :else y))]
     (with-meta
       (merge-with merge-values a b)
       (update (meta a) ::paths (fnil into []) (source-paths b)))))
  ([a b & more]
   (reduce merge-settings a (cons b more))))



;; ## File Utilities

(defn readable?
  "True if the process can read the given `File`."
  [^File file]
  (and file (.canRead file)))


(defn file?
  "True if the given `File` represents a regular file."
  [^File file]
  (and file (.isFile file)))


(defn directory?
  "True if the given `File` represents a directory."
  [^File file]
  (and file (.isDirectory file)))


(defn canonical-dir
  "Return the nearest canonical directory for the path. If path resolves to a
  file, the parent directory is returned."
  ^File
  [path]
  (let [file (-> path io/file .getAbsoluteFile .getCanonicalFile)]
    (if (.isDirectory file)
      file
      (.getParentFile file))))


(defn source-file?
  "True if the file is a recognized source file."
  [config ^File file]
  (and (file? file)
       (readable? file)
       (re-seq (:file-pattern config) (.getName file))))


(defn ignored?
  "True if the file should be ignored."
  [config exclude-globs ^File file]
  (or
    (some
      (fn test-rule
        [rule]
        (cond
          (string? rule)
          (= rule (.getName file))

          (pattern? rule)
          (boolean (re-seq rule (.getCanonicalPath file)))

          :else false))
      (:file-ignore config))
    (some
      (fn test-glob
        [glob]
        (let [path-matcher (.getPathMatcher (FileSystems/getDefault) (str "glob:" glob))]
          (.matches path-matcher (.toPath file))))
      exclude-globs)))



;; ## Configuration Files

(def ^:const file-name
  "Name which indicates a cljstyle configuration file."
  ".cljstyle")


(defn read-config
  "Read a configuration file. Throws an exception if the read fails or the
  contents are not valid configuration settings."
  [^File file]
  (let [path (.getAbsolutePath file)]
    (->
      (try
        (read-string (slurp file))
        (catch Exception ex
          (throw (ex-info (str "Error loading configuration from file: "
                               path "\n" (.getSimpleName (class ex))
                               ": " (.getMessage ex))
                          {:type ::invalid
                           :path path}
                          ex))))
      (as-> config
        (if (s/valid? ::settings config)
          (vary-meta config assoc ::paths [path])
          (throw (ex-info (str "Invalid configuration loaded from file: " path
                               "\n" (s/explain-str ::settings config))
                          {:type ::invalid
                           :path path})))))))


(defn dir-config
  "Return the map of cljstyle configuration from the file in the given directory,
  if it exists and is readable. Returns nil if the configuration is not present
  or is invalid."
  [^File dir]
  (let [file (io/file dir file-name)]
    (when (and (file? file) (readable? file))
      (read-config file))))


(defn find-up
  "Search upwards from a starting path, collecting cljstyle configuration
  files. Returns a sequence of configuration maps read, with shallower paths
  ordered earlier.

  The search will include configuration in the starting path if it is a
  directory, and will terminate after `limit` recursions or once it hits the
  filesystem root or a directory the user can't read."
  [start limit]
  {:pre [start (pos-int? limit)]}
  (loop [configs ()
         dir (canonical-dir start)
         limit limit]
    (if (and (pos? limit) (directory? dir) (readable? dir))
      ;; Look for config file and recurse upward.
      (recur (if-let [config (dir-config dir)]
               (cons config configs)
               configs)
             (.getParentFile dir)
             (dec limit))
      ;; No further to recurse.
      configs)))
