(ns orchard.namespace
  "Utilities for resolving and loading namespaces"
  (:require [clojure.java.io :as io]
            [clojure.tools.namespace.file :as ns-file]
            [clojure.tools.namespace.find :as ns-find]
            [orchard.classloader :refer [class-loader]]
            [orchard.classpath :as cp]
            [clojure.string :as str]
            [orchard.misc :as misc])
  (:import java.util.jar.JarFile))

;;; Namespace Loading

(defn ensure-namespace
  "Require `ns` (no-op if already loaded). Return the symbol if successful,
  and `nil` if this fails."
  [ns]
  (try (doto (symbol ns) require)
       (catch Exception _)))

;;; Project Namespaces
;;
;; These methods search project sources on the classpath. Non-classpath source
;; files, documentation code, etc within the project directory are ignored.
(def jar-namespaces
  (->> (cp/classpath-jarfiles)
       (mapcat ns-find/find-namespaces-in-jarfile)
       (into #{})))

(def project-root
  (str (System/getProperty "user.dir")
       (System/getProperty "file.separator")))

(defn project-namespaces
  "Find all namespaces defined in source paths within the current project."
  []
  (let [project-pred (if (misc/os-windows?)
                       ;; On Windows we want to do case-insensitive path comparison
                       #(.startsWith
                         (str/lower-case (str %))
                         (str/lower-case project-root))
                       #(.startsWith (str %) project-root))]
    (->> (filter (memfn isDirectory) (cp/classpath (class-loader)))
         (filter project-pred)
         (mapcat ns-find/find-namespaces-in-dir))))

(defn inlined-dependency?
  "Returns true if the namespace matches one of our, or eastwood's,
   inlined dependencies."
  [namespace]
  (let [ns-name (str (ns-name namespace))]
    (or
     ;; rewritten by mranderson
     (.startsWith ns-name "deps.")
     (.startsWith ns-name "mranderson")
     (.startsWith ns-name "cider.inlined-deps")
     ;; rewritten by dolly
     (.startsWith ns-name "eastwood.copieddeps"))))

(defn internal-namespace?
  "Returns true if the namespace matches the given prefixes."
  [namespace & [prefixes]]
  (let [ns-name (str (ns-name namespace))]
    (->> prefixes
         (map re-pattern)
         (map #(re-find % ns-name))
         (some (complement nil?)))))

(defn loaded-namespaces
  "Return all loaded namespaces, except those coming from inlined dependencies.
  `filter-regexps` is used to filter out namespaces matching regexps."
  [& [filter-regexps]]
  (->> (all-ns)
       (remove inlined-dependency?)
       (remove #(internal-namespace? % filter-regexps))
       (map ns-name)
       (map name)
       (sort)))

(defn loaded-project-namespaces
  "Return all loaded namespaces defined in the current project."
  []
  (->> (project-namespaces)
       (filter (set (map ns-name (all-ns))))
       sort))

(defn load-project-namespaces
  "Require and return all namespaces validly defined in the current project."
  []
  (->> (project-namespaces)
       (map ensure-namespace)
       (filter identity)
       sort))

;;; Finding a namespace's file.
(defn- jar-file?
  "Returns true if file is a normal file with a .jar or .JAR extension."
  [f]
  (let [file (io/file f)]
    (and (.isFile file)
         (.endsWith (.. file getName toLowerCase) ".jar"))))

(defn- get-clojure-sources-in-jar
  [^JarFile jar]
  (let [path-to-jar (.getName jar)]
    (map #(str "jar:file:" path-to-jar "!/" %) (ns-find/sources-in-jar jar))))

(defn- all-clj-files-on-cp []
  (let [dirs-on-cp (filter #(.isDirectory %) (cp/classpath))
        jars-on-cp (map #(JarFile. %) (filter jar-file? (cp/classpath)))]
    (concat (->> dirs-on-cp
                 (mapcat ns-find/find-sources-in-dir)
                 (map #(.getAbsolutePath %)))
            (mapcat get-clojure-sources-in-jar jars-on-cp))))

(defn ns-path
  "Return the path to a file containing namespace `ns`.
  `ns` can be a Namespace object or the name of a namespace."
  [ns]
  (try
    (let [ns (if (instance? clojure.lang.Namespace ns)
               (ns-name ns) (symbol ns))]
      (loop [paths (all-clj-files-on-cp)]
        (when (seq paths)
          (let [file-ns (second (ns-file/read-file-ns-decl (first paths)))]
            (if (= file-ns ns)
              (first paths)
              (recur (rest paths)))))))
    (catch Throwable _ nil)))

(defn has-tests?
  "Return a truthy value if the namespace has any vars with `:test` metadata."
  [ns]
  (seq (filter (comp :test meta val) (ns-interns ns))))
