(ns sci.impl.evaluator
  {:no-doc true}
  (:refer-clojure :exclude [eval])
  (:require
   [clojure.string :as str]
   [sci.impl.faster :as faster :refer [get-2 deref-1]]
   [sci.impl.fns :as fns]
   [sci.impl.interop :as interop]
   [sci.impl.load :as load]
   [sci.impl.macros :as macros]
   [sci.impl.records :as records]
   [sci.impl.types :as t]
   [sci.impl.utils :as utils :refer [throw-error-with-location
                                     rethrow-with-location-of-node
                                     set-namespace!
                                     kw-identical?]]
   [sci.impl.vars :as vars])
  #?(:cljs (:require-macros [sci.impl.evaluator :refer [def-fn-call resolve-symbol]])))

(declare eval fn-call)

#?(:clj (set! *warn-on-reflection* true))

(def #?(:clj ^:const macros :cljs macros)
  '#{do and or quote fn def defn
     lazy-seq try syntax-quote case . in-ns set!
     ;; TODO: make normal function
     require})

;;;; Evaluation

(defn eval-and
  "The and macro from clojure.core. Note: and is unrolled in the analyzer, this is a fallback."
  [ctx args]
  (let [args (seq args)]
    (loop [args args]
      (if args
        (let [x (first args)
              v (eval ctx x)]
          (if v
            (let [xs (next args)]
              (if xs
                (recur xs) v)) v))
        true))))

(defn eval-or
  "The or macro from clojure.core. Note: or is unrolled in the analyzer, this is a fallback."
  [ctx args]
  (let [args (seq args)]
    (loop [args args]
      (when args
        (let [x (first args)
              v (eval ctx x)]
          (if v v
              (let [xs (next args)]
                (if xs (recur xs)
                    v))))))))

(defn eval-let
  "The let macro from clojure.core"
  [ctx let-bindings exprs]
  (let [ctx (loop [ctx ctx
                   let-bindings let-bindings]
              (let [let-name (first let-bindings)
                    let-bindings (rest let-bindings)
                    let-val (first let-bindings)
                    rest-let-bindings (next let-bindings)
                    v (eval ctx let-val)
                    bindings (faster/get-2 ctx :bindings)
                    bindings (faster/assoc-3 bindings let-name v)
                    ctx (faster/assoc-3 ctx :bindings bindings)]
                (if-not rest-let-bindings
                  ctx
                  (recur ctx
                         rest-let-bindings))))]
    (when exprs
      (loop [exprs exprs]
        (let [e (first exprs)
              ret (eval ctx e)
              nexprs (next exprs)]
          (if nexprs (recur nexprs)
              ret))))))

(defn eval-def
  [ctx var-name init]
  (let [init (eval ctx init)
        m (meta var-name)
        m (eval ctx m) ;; m is marked with eval op in analyzer only when necessary
        cnn (vars/getName (:ns m))
        assoc-in-env
        (fn [env]
          (let [the-current-ns (get (get env :namespaces) cnn)
                prev (get the-current-ns var-name)
                prev (if-not (vars/var? prev)
                       (vars/->SciVar prev (symbol (str cnn) (str var-name))
                                      (meta prev)
                                      false)
                       prev)
                v (if (kw-identical? :sci.impl/var.unbound init)
                    (doto prev
                      (alter-meta! merge m))
                    (do (vars/bindRoot prev init)
                        (alter-meta! prev merge m)
                        prev))
                the-current-ns (assoc the-current-ns var-name v)]
            (assoc-in env [:namespaces cnn] the-current-ns)))
        env (swap! (:env ctx) assoc-in-env)]
    ;; return var
    (get (get (get env :namespaces) cnn) var-name)))

(defmacro resolve-symbol [ctx sym]
  `(.get ^java.util.Map
         (.get ~(with-meta ctx
                  {:tag 'java.util.Map}) :bindings) ~sym))

(declare eval-string*)

(defn eval-case
  [ctx [_case {:keys [:case-map :case-val :case-default]}]]
  (let [v (eval ctx case-val)]
    (if-let [[_ found] (find case-map v)]
      (eval ctx found)
      (if (vector? case-default)
        (eval ctx (second case-default))
        (throw (new #?(:clj IllegalArgumentException :cljs js/Error)
                    (str "No matching clause: " v)))))))

(defn eval-try
  [ctx expr]
  (let [{:keys [:body :catches :finally]} (:sci.impl/try expr)]
    (try
      (binding [utils/*in-try* true]
        (eval ctx body))
      (catch #?(:clj Throwable :cljs js/Error) e
        (if-let
         [[_ r]
          (reduce (fn [_ c]
                    (let [clazz (:class c)]
                      (when (instance? clazz e)
                        (reduced
                         [::try-result
                          (eval (assoc-in ctx [:bindings (:binding c)]
                                          e)
                                (:body c))]))))
                  nil
                  catches)]
          r
          (rethrow-with-location-of-node ctx e body)))
      (finally
        (eval ctx finally)))))

(defn eval-throw [ctx [_throw ex]]
  (let [ex (eval ctx ex)]
    (throw ex)))

;;;; Interop

(defn eval-static-method-invocation [ctx expr]
  (interop/invoke-static-method (first expr)
                                ;; eval args!
                                (map #(eval ctx %) (rest expr))))

(defn eval-constructor-invocation [ctx [_new #?(:clj class :cljs constructor) args]]
  (let [args (map #(eval ctx %) args)] ;; eval args!
    (interop/invoke-constructor #?(:clj class :cljs constructor) args)))

#?(:clj
   (defn super-symbols [clazz]
     ;; (prn clazz '-> (map #(symbol (.getName ^Class %)) (supers clazz)))
     (map #(symbol (.getName ^Class %)) (supers clazz))))

(defn eval-instance-method-invocation [{:keys [:class->opts] :as ctx}
                                       [_dot instance-expr method-str args :as _expr]]
  (let [instance-meta (meta instance-expr)
        tag-class (:tag-class instance-meta)
        instance-expr* (eval ctx instance-expr)]
    (if (and (map? instance-expr*)
             (:sci.impl/record (meta instance-expr*))) ;; a sci record
      (get instance-expr* (keyword (subs method-str 1)))
      (let [instance-class (or tag-class (#?(:clj class :cljs type) instance-expr*))
            instance-class-name #?(:clj (.getName ^Class instance-class)
                                   :cljs (.-name instance-class))
            instance-class-symbol (symbol instance-class-name)
            allowed? (or
                      (get class->opts :allow)
                      (get class->opts instance-class-symbol))
            ^Class target-class (if allowed? instance-class
                                    (when-let [f (:public-class ctx)]
                                      (f instance-expr*)))]
        ;; we have to check options at run time, since we don't know what the class
        ;; of instance-expr is at analysis time
        (when-not target-class
          (throw-error-with-location (str "Method " method-str " on " instance-class " not allowed!") instance-expr))
        (let [args (map #(eval ctx %) args)] ;; eval args!
          (interop/invoke-instance-method instance-expr* target-class method-str args))))))

;;;; End interop

;;;; Namespaces

(defn eval-in-ns [ctx [_in-ns ns-expr]]
  (let [ns-sym (eval ctx ns-expr)]
    (set-namespace! ctx ns-sym nil)
    nil))

(declare eval-form)

(defn eval-resolve
  ([ctx sym]
   (let [sym (eval ctx sym)]
     (second (@utils/lookup ctx sym false))))
  ([ctx env sym]
   (when-not (contains? env sym)
     (let [sym (eval ctx sym)]
       (second (@utils/lookup ctx sym false))))))

(vreset! utils/eval-resolve-state eval-resolve)

;;;; End namespaces

;;;; Import

(defn eval-import [ctx & import-symbols-or-lists]
  ;;(prn import-symbols-or-lists)
  (let [specs (map #(if (and (seq? %) (= 'quote (first %))) (second %) %)
                   import-symbols-or-lists)
        env (:env ctx)]
    (reduce (fn [_ spec]
              (let [[package classes]
                    (if (symbol? spec)
                      (let [s (str spec)
                            last-dot (str/last-index-of s ".")
                            package+class-name
                            (if last-dot
                              [(symbol (subs s 0 last-dot))
                               [(symbol (subs s (inc last-dot) (count s)))]]
                              [nil [spec]])]
                        package+class-name)
                      (let [p (first spec)
                            cs (rest spec)]
                        [p cs]))]
                (reduce (fn [_ class]
                          (let [fq-class-name (symbol (if package (str package "." class)
                                                          class))]
                            (if-let [clazz (interop/resolve-class ctx fq-class-name)]
                              (let [cnn (vars/current-ns-name)]
                                (swap! env assoc-in [:namespaces cnn :imports class] fq-class-name)
                                clazz)
                              (if-let [rec (records/resolve-record-or-protocol-class ctx package class)]
                                (let [cnn (vars/current-ns-name)]
                                  (swap! env assoc-in [:namespaces cnn class] rec)
                                  rec)
                                (throw (new #?(:clj Exception :cljs js/Error)
                                            (str "Unable to resolve classname: " fq-class-name)))))))
                        nil
                        classes)))
            nil
            specs)))

;;;; End import

(defn eval-set! [ctx [_ obj v]]
  (let [obj (eval ctx obj)
        v (eval ctx v)]
    (if (vars/var? obj)
      (t/setVal obj v)
      (throw (ex-info (str "Cannot set " obj " to " v) {:obj obj :v v})))))

(declare eval-string)

(defn eval-do
  "Note: various arities of do have already been unrolled in the analyzer."
  [ctx exprs]
  (let [exprs (seq exprs)]
    (loop [exprs exprs]
      (when exprs
        (let [ret (eval ctx (first exprs))]
          (if-let [exprs (next exprs)]
            (recur exprs)
            ret))))))

(vreset! utils/eval-do* eval-do)

(macros/deftime
  ;; This macro generates a function of the following form for 20 arities:
  #_(defn fn-call [ctx f args]
      (case (count args)
        0 (f)
        1 (let [arg (eval ctx (first args))]
            (f arg))
        2 (let [arg1 (eval ctx (first args))
                args (rest args)
                arg2 (eval ctx (first args))]
            (f arg1 arg2))
        ,,,
        (let [args (mapv #(eval ctx %) args)]
          (apply f args))))
  (defmacro def-fn-call []
    (let [cases
          (mapcat (fn [i]
                    [i (let [arg-syms (map (fn [_] (gensym "arg")) (range i))
                             args-sym 'args ;; (gensym "args")
                             let-syms (interleave arg-syms (repeat args-sym))
                             let-vals (interleave (repeat `(eval ~'ctx (first ~args-sym)))
                                                  (repeat `(rest ~args-sym)))
                             let-bindings (vec (interleave let-syms let-vals))]
                         `(let ~let-bindings
                            (~'f ~@arg-syms)))]) (range 20))
          cases (concat cases ['(let [args (mapv #(eval ctx %) args)]
                                  (apply f args))])]
      ;; Normal apply:
      #_`(defn ~'fn-call ~'[ctx f args]
           (apply ~'f (map #(eval ~'ctx %) ~'args)))
      `(defn ~'fn-call ~'[ctx f args]
         ;; TODO: can we prevent hitting this at all, by analyzing more efficiently?
         ;; (prn :count ~'f ~'(count args) ~'args)
         (case ~'(count args)
           ~@cases)))))

(def-fn-call)

(defn eval-special-call [ctx f-sym expr]
  (case (utils/strip-core-ns f-sym)
    ;; do (eval-do ctx expr)
    and (eval-and ctx (rest expr))
    or (eval-or ctx (rest expr))
    lazy-seq (new #?(:clj clojure.lang.LazySeq
                     :cljs cljs.core/LazySeq)
                  #?@(:clj []
                      :cljs [nil])
                  (eval ctx (second expr))
                  #?@(:clj []
                      :cljs [nil nil]))
    ;; recur (fn-call ctx (comp fns/->Recur vector) (rest expr))
    case (eval-case ctx expr)
    try (eval-try ctx expr)
    ;; interop
    new (eval-constructor-invocation ctx expr)
    . (eval-instance-method-invocation ctx expr)
    throw (eval-throw ctx expr)
    in-ns (eval-in-ns ctx expr)
    set! (eval-set! ctx expr)
    refer (apply load/eval-refer ctx (rest expr))
    require (apply load/eval-require ctx (with-meta (rest expr)
                                      (meta expr)))
    use (apply load/eval-use ctx (with-meta (rest expr)
                                   (meta expr)))
    ;; resolve works as a function so this should not be necessary
    ;; resolve (eval-resolve ctx (second expr))
    ;;macroexpand-1 (macroexpand-1 ctx (eval ctx (second expr)))
    ;; macroexpand (macroexpand ctx (eval ctx (second expr)))
    import (apply eval-import ctx (rest expr))
    quote (second expr)))

(defn eval-call [ctx expr]
  (try (let [f (first expr)
             eval? (instance? sci.impl.types.EvalFn f)
             op (when-not eval?
                  (some-> (meta f) (get-2 :sci.impl/op)))]
         (cond
           (and (symbol? f) (not op))
           (eval-special-call ctx f expr)
           (kw-identical? op :static-access)
           (eval-static-method-invocation ctx expr)
           :else
           (let [f (if (or op eval?)
                     (eval ctx f)
                     f)]
             (if (ifn? f)
               (fn-call ctx f (rest expr))
               (throw (new #?(:clj Exception :cljs js/Error)
                           (str "Cannot call " (pr-str f) " as a function.")))))))
       (catch #?(:clj Throwable :cljs js/Error) e
         (rethrow-with-location-of-node ctx e expr))))

(defn handle-meta [ctx m]
  ;; Sometimes metadata needs eval. In this case the metadata has metadata.
  (-> (if-let [mm (meta m)]
        (if (when mm (get-2 mm :sci.impl/op))
          (eval ctx m)
          m)
        m)
      (dissoc :sci.impl/op)))

(defn eval
  [ctx expr]
  (try
    (cond (instance? sci.impl.types.EvalFn expr)
          (let [f (.-f ^sci.impl.types.EvalFn expr)]
            (f ctx))
          (instance? sci.impl.types.EvalVar expr)
          (let [v (.-v ^sci.impl.types.EvalVar expr)]
            (deref-1 v))
          :else
          (let [m (meta expr)
                op (when m (get-2 m :sci.impl/op))
                ret
                (if
                 (not op) expr
                    ;; TODO: moving this up increased performance for #246. We can
                    ;; probably optimize it further by not using separate keywords for
                    ;; one :sci.impl/op keyword on which we can use a case expression
                 (case op
                   :call (eval-call ctx expr)
                   :try (eval-try ctx expr)
                   :fn (let [fn-meta (:sci.impl/fn-meta expr)
                             the-fn (fns/eval-fn ctx eval expr)
                             fn-meta (when fn-meta (handle-meta ctx fn-meta))]
                         (if fn-meta
                           (vary-meta the-fn merge fn-meta)
                           the-fn))
                   :static-access (interop/get-static-field expr)
                   :deref! (let [v (first expr)
                                 v (if (vars/var? v) @v v)
                                 v (force v)]
                             v)
                   :resolve-sym (resolve-symbol ctx expr)
                      ;; needed for when a needs-ctx fn is passed as hof
                   needs-ctx (if (identical? op utils/needs-ctx)
                               (partial expr ctx)
                                  ;; this should never happen, or if it does, it's
                                  ;; someone trying to hack
                               (throw (new #?(:clj Exception :cljs js/Error)
                                           (str "unexpected: " expr ", type: " (type expr), ", meta:" (meta expr)))))
                   (cond (map? expr) (with-meta (zipmap (map #(eval ctx %) (keys expr))
                                                        (map #(eval ctx %) (vals expr)))
                                       (handle-meta ctx m))
                         :else (throw (new #?(:clj Exception :cljs js/Error)
                                           (str "unexpected: " expr ", type: " (type expr), ", meta:" (meta expr)))))))]
            ;; for debugging:
            ;; (prn :eval expr (meta expr) '-> ret (meta ret))
            ret))
    (catch #?(:clj Throwable :cljs js/Error) e
      (rethrow-with-location-of-node ctx e expr))))

(vreset! utils/eval* eval)
