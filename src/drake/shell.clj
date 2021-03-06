(ns drake.shell
  (:require [clojure.string :refer [join]]
            [clojure.tools.logging :as log :refer [debug]]
            [clojure.java.io :refer [as-file]]
            [slingshot.slingshot :refer [throw+]]
            [fs.core :as fs]
            [drake.stdin :as stdin]))

;; Copied in full from clojure.java.shell
(defn- ^"[Ljava.lang.String;" as-env-strings
  "Helper so that callers can pass a Clojure map for the :env to sh."
  [arg]
  (cond
   (nil? arg) nil
   (map? arg) (into-array String (map (fn [[k v]] (str (name k) "=" v)) arg))
   true arg))

(defn- multiplex-stream
  "Duplicates a readable stream.

     in       A Java readable stream.
     outs     A vector of objects supporting write function
              (can be output streams or writers)."
  [in outs]
  ;; In order to try to preserve the order of stdout and stderr,
  ;; we do as little buffering as possible, copying the streams
  ;; byte-by-byte and not using Java readers
  (while
      (let [c (.read in)]
        (if-not (neg? c)
          (doall (map #(.write % c) outs)))))
  (doseq [c outs] (.flush c)))

(defn- copy-stdin
  "Copies stdin to a child process."
  [^Process proc]
  (let [child-stdin-stream (.getOutputStream proc)]
    (try
      (while (stdin/process-line-stdin
              #(do
                 (if %
                   (do
                     (.write child-stdin-stream (.getBytes (str % "\n")))
                     ;; need to flush here, it's buffered by default
                     (.flush child-stdin-stream))
                   ;; if we got EOF on our stdin,
                   ;; close the child's stdin as well
                   (.close child-stdin-stream))
                 %)))   ;; return the string we've read (nil if EOF)
      (catch java.lang.InterruptedException _)    ;; if we interrupt
      (catch java.io.IOException _))))            ;; if the process dies

(defn shell-cmd-prelude [& unix-suffix]
  (if (.startsWith (System/getProperty "os.name") "Win")
       ["cmd" "/C"]
       (cons (or (get (System/getenv) "SHELL")
                 (do (log/warnf "$SHELL not set; defaulting to /bin/sh")
                     "/bin/sh"))
             unix-suffix)))

(defn shell
  "Runs the specified command and arguments using the system shell.

   Additional options can be specified:
     :out           A vector of java.io.Writer objects. The output of
                    the child process is written to each of the objects
                    given here. Use Clojure's (writer) function to copy
                    output into a file, java.io.StringWriter to get
                    it as a string, or System/out to use the current stdout.
                    Example:
                      (let [w (java.io.StringWriter.)]
                        (shell cmd :out [(writer log-file-name) System/out w])
                        ;; output is written to stdout,
                        ;; and also saved to log-file-name
                        (str w)    ;; <- and also here it is in a string form
                      )
                    The default value for this parameter is [System/out].
     :err           Same for standard error.
     :env           New environment.
     :replace-env   If true, replaces the existing environment,
                    adds by default.
     :die           Throws an exception if the exit code is non-zero.
     :use-shell     If true, use system's shell to run the provided command.
                    The command and all its argument are concatenated via
                    a space and given to $SHELL -c.
     :no-stdin      Do not redirect stdin to the child process

   Returns the child process' exit code.

   Loosely based on clojure.java.shell/sh."
  [& args]
  (let [[cmd {:keys [out err die use-shell no-stdin env replace-env] :as opts}]
        (split-with string? args)
        env (as-env-strings (if replace-env
                              env
                              (merge (into {} (System/getenv)) env)))
        ^"[Ljava.lang.String;" cmd-for-exec
        (into-array String
                    (if-not use-shell
                      cmd
                      `[~@(shell-cmd-prelude "-c")
                        ~(join " "  cmd)]))
        proc (.exec (Runtime/getRuntime)
                    cmd-for-exec
                    ^"[Ljava.lang.String;" env
                    (fs/file fs/*cwd*))]
    ;; Pass stdin to process unless :no-stdin option is set
    ;; Usually set when async is on
    (let [^Thread stdin  (and (not no-stdin) (Thread. #(copy-stdin proc)))
          ;; because we're starting two threads to process stdout & stderr,
          ;; there's NO GUARANTEE that lines output to these streams will appear
          ;; in correct order relative to each other. if the child process
          ;; outputs rapidly to both stdout and stderr, the thread that
          ;; processes stdout (for example) is likely to overprocess, and stderr
          ;; messages will appear delayed.
          ;; the situation could be better if we set up a select loop to wait
          ;; on both file descriptors at the same time (instead of creating
          ;; two threads), which is probably how most terminals are implemented.
          stdout (Thread. #(multiplex-stream
                            (.getInputStream proc)
                            (if out out [System/out])))
          stderr (Thread. #(multiplex-stream
                            (.getErrorStream proc)
                            (if err err [System/err])))]
      ;; start threads
      (doseq [^Thread t [stdin stdout stderr]] (when t (.start t)))
      ;; we have to wait until stdout and stderr threads finish,
      ;; otherwise when the process exits, we may not have finished
      ;; copying; this does not apply to stdin since it's blocked on user
      ;; input
      (doseq [^Thread t [stdout stderr]] (.join t))
      (let [exit-code (.waitFor proc)]
        (when stdin
          ;; the process exited at this point, interrupt the thread
          ;; that is waiting on user's input
          (.interrupt stdin)
          ;; now we can wait for its completion
          (.join stdin))
        (if (and (not= 0 exit-code) die)
          (let [message (str "shell command failed with exit code " exit-code)]
            (Thread/sleep 500) ;; let stdout/stderr finish printing?
            (throw (ex-info message (merge {:msg message
                                            :cmd (seq cmd-for-exec)
                                            :opts (dissoc opts :env)
                                            :exit-code exit-code}
                                           (when (and (not use-shell)
                                                      (= 2 (count cmd)))
                                             (let [file (fs/file (second cmd))]
                                               (when (fs/exists? file)
                                                 {:file (slurp file)}))))))))))))
