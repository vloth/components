(ns parenthesin.components.router.reitit-schema
  (:require [clojure.walk :as w]
            [com.stuartsierra.component :as component]
            [muuntaja.core :as m]
            [parenthesin.helpers.logs :as logs]
            [reitit.coercion.schema :as reitit.schema]
            [reitit.dev.pretty :as pretty]
            [reitit.http :as http]
            [reitit.http.coercion :as coercion]
            [reitit.http.interceptors.exception :as exception]
            [reitit.http.interceptors.multipart :as multipart]
            [reitit.http.interceptors.muuntaja :as muuntaja]
            [reitit.http.interceptors.parameters :as parameters]
            [reitit.pedestal :as pedestal]
            [reitit.ring :as ring]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]))

(defn- coercion-error-handler [status]
  (fn [exception _request]
    (logs/log :error exception :coercion-errors (:errors (ex-data exception)))
    {:status status
     :body (if (= 400 status)
             (str "Invalid path or request parameters, with the following errors: "
                  (:errors (ex-data exception)))
             "Error checking path or request parameters.")}))

(defn- exception-info-handler [exception _request]
  (logs/log :error exception "Server exception:" :exception exception)
  {:status 500
   :body   "Internal error."})

(def router-settings
  {;:reitit.interceptor/transform dev/print-context-diffs ;; pretty context diffs
     ;;:validate spec/validate ;; enable spec validation for route data
     ;;:reitit.spec/wrap spell/closed ;; strict top-level validation
   :exception pretty/exception
   :data {:coercion reitit.schema/coercion
          :muuntaja (m/create
                     (-> m/default-options
                         (assoc-in [:formats "application/json" :decoder-opts :bigdecimals] true)))
          :interceptors [;; swagger feature
                         swagger/swagger-feature
                             ;; query-params & form-params
                         (parameters/parameters-interceptor)
                             ;; content-negotiation
                         (muuntaja/format-negotiate-interceptor)
                             ;; encoding response body
                         (muuntaja/format-response-interceptor)
                             ;; exception handling
                         (exception/exception-interceptor
                          (merge
                           exception/default-handlers
                           {:reitit.coercion/request-coercion  (coercion-error-handler 400)
                            :reitit.coercion/response-coercion (coercion-error-handler 500)
                            clojure.lang.ExceptionInfo exception-info-handler}))
                             ;; decoding request body
                         (muuntaja/format-request-interceptor)
                             ;; coercing response bodys
                         (coercion/coerce-response-interceptor)
                             ;; coercing request parameters
                         (coercion/coerce-request-interceptor)
                             ;; multipart
                         (multipart/multipart-interceptor)]}})

(defn add-support-var-handlers
  "Adds support to var handlers to a more REPL friendly experience.
  When dev? var handlers are wrapped in a function to allow replacing var values in runtime.
  When not dev? the values are just used instead."
  [routes dev?]
  (letfn [(wrap [original-handler]
            (cond (fn? original-handler) original-handler
                  dev? (fn [& args] (apply original-handler args))
                  (var? original-handler) (var-get original-handler)))]
    (w/postwalk
     #(if (and (coll? %) (= (first %) :handler))
        [:handler (wrap (second %))]
        %)
     routes)))

(defn router [routes env]
  (pedestal/routing-interceptor
   (http/router (add-support-var-handlers routes (= :dev env))
                router-settings)
   ;; optional default ring handler (if no routes have matched)
   (ring/routes
    (swagger-ui/create-swagger-ui-handler
     {:path "/"
      :config {:validatorUrl nil
               :operationsSorter "alpha"}})
    (ring/create-resource-handler)
    (ring/create-default-handler))))

(defrecord Router [config routes]
  component/Lifecycle
  (start [this]
    (assoc this :router (router routes (get-in config [:config :env]))))

  (stop  [this] this))

(defn new-router
  [routes]
  (map->Router {:routes routes}))
