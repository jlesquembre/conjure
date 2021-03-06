(ns conjure.prepl
  "Remote prepl connection management and selection."
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async :as a]
            [clojure.core.server :as server]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [conjure.util :as util]
            [conjure.ui :as ui]
            [conjure.code :as code])
  (:import [java.io PipedInputStream PipedOutputStream]))

(s/def ::expr util/regexp?)
(s/def ::tag keyword?)
(s/def ::port number?)
(s/def ::lang #{:clj :cljs})
(s/def ::host string?)
(s/def ::new-conn (s/keys :req-un [::tag ::port]
                          :opt-un [::expr ::lang ::host]))

(defonce ^:private conns! (atom {}))
(def ^:private default-exprs
  {:clj #"\.cljc?$"
   :cljs #"\.clj(s|c)$"})

(defn remove!
  "Remove the connection under the given tag. Shuts it down cleanly and blocks
  until it's done."
  [tag]
  (when-let [conn (get @conns! tag)]
    (log/info "Removing" tag)
    (ui/info "Removing" tag)
    (swap! conns! dissoc tag)

    ;; read-chan is closed when the remote-prepl exits. This
    ;; pattern of closing two here and then waiting for the
    ;; read-chan to return a nil (which it will when closed)
    ;; ensures that removal isn't complete until the remote-prepl is done.
    ;; This prevents some weird race conditions with node connections.
    (let [{:keys [eval-chan ret-chan read-chan]} (:chans conn)]
      (a/close! eval-chan)
      (a/close! ret-chan)
      (loop []
        (when-not (nil? (a/<!! read-chan))
          (recur))))))

(defn remove-all! []
  (doseq [tag (keys @conns!)]
    (remove! tag)))

(defn connect
  "Connect to a prepl and return channels to interact with it. When the eval
  channel closes it cascades through the system and eventually closes the read
  channel. We can use this fact to await the read channel's closure to know
  when the closing is complete. Handy!"
  [{:keys [tag host port]}]
  (let [[eval-chan read-chan] (repeatedly #(a/chan 32))
        input (PipedInputStream.)
        output (PipedOutputStream. input)]

    (util/thread
      "reader loop"
      (with-open [reader (io/reader input)]
        (try
          (log/info "Connecting through remote-prepl" tag)
          (server/remote-prepl
            host port reader
            (fn [out]
              (log/trace "Read from remote-prepl" tag "-" out)
              (a/>!! read-chan out))
            :valf identity)

          (catch Exception e
            (log/error "Error from remote-prepl:" e)
            (ui/error "Error from" tag e))

          (finally
            (log/trace "Exited remote-prepl, cleaning up" tag)
            (a/close! read-chan)
            (remove! tag)))))

    (util/thread
      "writer loop"
      (with-open [writer (io/writer output)]
        (try
          (loop []
            (when-let [code (a/<!! eval-chan)]
              (log/trace "Writing to tag:" tag "-" code)
              (util/write writer code)
              (recur)))

          (catch Exception e
            (log/error "Error from eval-chan writing:" e))

          (finally
            (log/trace "Exited eval-chan loop, cleaning up" tag)
            (util/write writer ":repl/quit\n")))))

    {:eval-chan eval-chan
     :read-chan read-chan}))

(defn add!
  "Remove any existing connection under :tag then create a new connection."
  [{:keys [tag lang expr host port]
    :or {host "127.0.0.1"
         lang :clj}}]

  (remove! tag)

  (log/info "Adding" tag host port)
  (ui/info "Adding" tag)

  (let [ret-chan (a/chan 32)
        conn {:tag tag
              :lang lang
              :host host
              :port port
              :expr (or expr (get default-exprs lang))
              :chans (merge
                       {:ret-chan ret-chan}
                       (connect {:tag tag
                                 :host host
                                 :port port}))}
        prelude (code/prelude-str {:lang lang})]

    (swap! conns! assoc tag conn)

    (log/trace "Sending prelude:" prelude)
    (a/>!! (get-in conn [:chans :eval-chan]) prelude)
    (log/trace "Prelude result:" (a/<!! (get-in conn [:chans :read-chan])))

    (util/thread
      "read-chan handler"
      (loop []
        (when-let [out (a/<!! (get-in conn [:chans :read-chan]))]
          (log/trace "Read value from" (:tag conn) "-" out)
          (let [out (cond-> out
                      (contains? #{:tap :ret} (:tag out))
                      (update :val code/parse-code))]
            (if (= (:tag out) :ret)
              (a/>!! ret-chan out)
              (ui/result {:conn conn, :resp out})))
          (recur))))))

(defn conns
  "Without a path it'll return all current connections. With a path it finds
  any connection who's :expr matches that string."
  ([] (vals @conns!))
  ([path]
   (->> (conns)
        (filter
          (fn [{:keys [expr]}]
            (re-find expr path)))
        (seq))))

(defn status
  "Display the current status of the connections. This counts and lists with
  some connection information."
  []
  (let [conns (conns)
        intro (util/count-str conns "connection")
        conn-strs (for [{:keys [tag host port expr lang]} conns]
                    (str tag " @ " host ":" port " for " (pr-str expr) " (" lang ")"))]
    (ui/info (util/join-lines (into [intro] conn-strs)))))
