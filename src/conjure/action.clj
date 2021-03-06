(ns conjure.action
  "Things the user can do that probably trigger some sort of UI update."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [conjure.prepl :as prepl]
            [conjure.ui :as ui]
            [conjure.nvim :as nvim]
            [conjure.code :as code]
            [conjure.util :as util]))

(defn- current-ctx
  "An enriched version of the nvim ctx with matching prepl connections."
  ([] (current-ctx {}))
  ([{:keys [silent?] :or {silent? false}}]
   (let [ctx (nvim/current-ctx)
         conns (prepl/conns (:path ctx))]

     (when (and (empty? conns) (not silent?))
       (ui/error "No matching connections for" (:path ctx)))

     (merge ctx {:conns conns}))))

(defn- wrapped-eval
  "Wraps up code with environment specific padding, sends it off for evaluation
  and blocks until we get a result."
  [ctx {:keys [conn] :as opts}]
  (let [{:keys [eval-chan ret-chan]} (:chans conn)]
    (a/>!! eval-chan (code/eval-str ctx opts))

    ;; ClojureScript requires two evals:
    ;; * Call in-ns.
    ;; * Execute the provided code.
    ;; We throw away the in-ns result first.
    (when (= (:lang conn) :cljs)
      (a/<!! ret-chan))

    (a/<!! ret-chan)))

(defn- raw-eval
  "Unlike wrapped-eval, it will send the exact code it is given and then block
  for a response."
  [ctx {:keys [conn code]}]
  (let [{:keys [eval-chan ret-chan]} (:chans conn)]
    (a/>!! eval-chan code)
    (a/<!! ret-chan)))

;; The following functions are called by the user through commands.

(defn eval* [{:keys [code line]}]
  (when code
    (let [ctx (current-ctx)]
      (doseq [conn (:conns ctx)]
        (let [opts {:conn conn, :code code, :line line}]
          (ui/eval* opts)
          (ui/result {:conn conn, :resp (wrapped-eval ctx opts)}))))))

(defn doc [name]
  (let [ctx (current-ctx)]
    (doseq [conn (:conns ctx)]
      (let [code (code/doc-str {:conn conn, :name name})
            result (-> (wrapped-eval ctx {:conn conn, :code code})
                       (update :val second))]
        (ui/doc {:conn conn
                 :resp (cond-> result
                         (empty? (:val result))
                         (assoc :val (str "No doc for " name)))})))))

(defn eval-current-form []
  (let [{:keys [form origin]} (nvim/read-form)]
    (eval* {:code form
            :line (first origin)})))

(defn eval-root-form []
  (let [{:keys [form origin]} (nvim/read-form {:root? true})]
    (eval* {:code form
            :line (first origin)})))

(defn eval-selection []
  (let [{:keys [selection origin]} (nvim/read-selection)]
    (eval* {:code selection
            :line (first origin)})))

(defn eval-buffer []
  (eval* {:code (nvim/read-buffer)}))

(defn load-file* [path]
  (let [ctx (current-ctx)
        code (code/load-file-str path)]
    (doseq [conn (:conns ctx)]
      (let [opts {:conn conn, :code code, :path path}]
        (ui/load-file* opts)
        (ui/result {:conn conn, :resp (raw-eval ctx opts)})))))

(defn- completion-context [prefix]
  (when-let [{:keys [form cursor]} (nvim/read-form {:root? true})]
    (-> (util/split-lines form)
        (update (dec (first cursor))
                #(util/splice %
                              (- (second cursor) (count prefix))
                              (second cursor)
                              "__prefix__"))
        (util/join-lines))))

(defn completions [prefix]
  (let [ctx (current-ctx {:silent? true})
        context (completion-context prefix)]
    (->> (:conns ctx)
         (mapcat
           (fn [conn]
             (log/trace "Finding completions for" (str "\"" prefix "\"")
                        "in" (:path ctx))
             (let [code (code/completions-str ctx {:conn conn
                                                   :prefix prefix
                                                   :context context})]
               (-> (wrapped-eval ctx {:conn conn, :code code})
                   (get :val)
                   (second)
                   (->> (map
                          (fn [{:keys [candidate type ns package]}]
                            (let [menu (or ns package)]
                              (util/kw->snake-map
                                (cond-> {:word candidate
                                         :kind (subs (name type) 0 1)}
                                  menu (assoc :menu menu)))))))))))
         (dedupe))))

(defn definition [name]
  (let [ctx (current-ctx)
        lookup (fn [conn]
                 (-> (wrapped-eval ctx
                                   {:conn conn
                                    :code (code/definition-str {:conn conn
                                                                :name name})})
                     (get :val)
                     (second)))
        coord (some lookup (:conns ctx))]
    (if (vector? coord)
      (nvim/edit-at ctx coord)
      (do
        (log/warn "Non-vector definition result:" coord)
        (nvim/definition)))))

(defn run-tests [targets]
  (let [ctx (current-ctx)
        ns (:ns ctx)
        other-ns (if (str/ends-with? ns "-test")
                   (str/replace ns #"-test$" "")
                   (str ns "-test"))]
    (doseq [conn (:conns ctx)]
      (let [code (code/run-tests-str
                   {:conn conn
                    :targets (if (empty? targets)
                               (cond-> #{ns}
                                 (= (:lang conn) :clj) (conj other-ns))
                               targets)})]
        (ui/test* {:conn conn
                   :resp (-> (wrapped-eval ctx {:conn conn, :code code})
                             (update :val second))})))))

(defn run-all-tests [re]
  (let [ctx (current-ctx)]
    (doseq [conn (:conns ctx)]
      (let [code (code/run-all-tests-str {:re re, :conn conn})]
        (ui/test* {:conn conn
                   :resp (-> (wrapped-eval ctx {:conn conn, :code code})
                             (update :val second))})))))
