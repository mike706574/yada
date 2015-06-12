;; Copyright © 2015, JUXT LTD.

(ns yada.resource-options
  (:require [clojure.core.async.impl.protocols :as aip]
            [clojure.set :as set]
            [clojure.tools.logging :refer :all :exclude [trace]]
            [manifold.deferred :as d]
            [manifold.stream :refer (->source transform)]
            [yada.representation :refer (MediaType full-type)])
  (import [clojure.core.async.impl.protocols ReadPort]
          [yada.representation MediaTypeMap]
          [java.io File]
          [java.util Date]))

(defprotocol ResourceOptions
  (service-available? [_ ctx] "Return whether the service is available. Supply a function which can return a deferred, if necessary.")
  (interpret-service-available [_] "Whether the result of service-available? means that the service is actually available")
  (retry-after [_] "Given the result of service-available? implies the service is unavailable, interpret the result as a retry-after header value")

  (known-method? [_ method] "Whether the method is known. Supply a function which can return a deferred, if necessary.")

  (request-uri-too-long? [_ uri])

  (allowed-methods [_] "Return a set of the allowed methods. Must be determined at compile time (for purposes of introspection by tools). No async support.")

  (body [_ ctx] "Return a representation of the resource. Supply a function which can return a deferred, if necessary.")
  (produces [_] "Return the content-types that the resource can produce")
  (status [_ ctx] "Override the response status")
  (headers [_ ctx] "Override the response headers")

  (put! [_ ctx] "PUT to the resource")
  (post! [_ ctx] "POST to the resource")
  (delete! [_ ctx] "DELETE the resource")
  (interpret-post-result [_ ctx] "Return the request context, according to the result of post")

  (trace [_ req ctx] "Intercept tracing, providing an alternative implementation, return a Ring response map.")

  (authorize [_ ctx] "Authorize the request. When truthy, authorization is called with the value and used as the :authorization entry of the context, otherwise assumed unauthorized.")
  (authorization [o] "Given the result of an authorize call, a truthy value will be added to the context.")

  (format-event [_] "Format an individual event (server sent events)")

  (allow-origin [_ ctx] "If another origin (other than the resource's origin) is allowed, return the the value of the Access-Control-Allow-Origin header to be set on the response"))

(extend-protocol ResourceOptions
  Boolean
  (service-available? [b ctx] b)
  (interpret-service-available [b] b)
  (retry-after [b] nil)
  (known-method? [b method] b)
  (request-uri-too-long? [b _] b)
  (post [b ctx] b)
  (interpret-post-result [b ctx]
    (if b ctx (throw (ex-info "Failed to process POST" {}))))
  (authorize [b ctx] b)
  (authorization [b] nil)
  (allow-origin [b _] (when b "*"))

  clojure.lang.Fn
  (service-available? [f ctx]
    (let [res (f ctx)]
      (if (d/deferrable? res)
        (d/chain res #(service-available? % ctx))
        (service-available? res ctx))))

  (known-method? [f method]
    (let [res (known-method? (f method) method)]
      (if (d/deferrable? res)
        (d/chain res #(known-method? % method))
        res)))

  (request-uri-too-long? [f uri] (request-uri-too-long? (f uri) uri))

  #_(state [f ctx]
    (let [res (f ctx)]
      (cond
        ;; ReadPort is deferrable, but we want the non-deferrable handling in this case
        (satisfies? aip/ReadPort res)
        (state res ctx)

        ;; Deferrable
        (d/deferrable? res)
        (d/chain res #(state % ctx))

        :otherwise
        (state res ctx))))

  (body [f ctx]
    (let [res (f ctx)]
      (cond
        ;; If this is something we can take from, in the core.async
        ;; sense, then call body again. We need this clause here
        ;; because: (satisfies? d/Deferrable (a/chan)) => true, so
        ;; (deferrable?  (a/chan) is (consequently) true too.
        (satisfies? aip/ReadPort res)
        (body res ctx)

        ;; Deferrable
        (d/deferrable? res)
        (d/chain res #(body % ctx))

        :otherwise
        (body res ctx))))

  (produces [f] (produces (f)))

  (post [f ctx]
    (f ctx))

  (authorize [f ctx] (f ctx))

  (allow-origin [f ctx] (f ctx))
  (status [f ctx] (f ctx))

  manifold.deferred.Deferred
  (interpret-service-available [v]
    (d/chain v (fn [v] (interpret-service-available v))))

  String
  (body [s _] s)
  #_(state [s ctx] s)
  (interpret-post-result [s ctx]
    (assoc-in ctx [:response :body] s))
  (format-event [ev] [(format "data: %s\n" ev)])
  (produces [s] [s])

  MediaTypeMap
  (produces [m] [m])

  Number
  (service-available? [n _] n)
  (interpret-service-available [_] false)
  (retry-after [n] n)
  (request-uri-too-long? [n uri]
    (request-uri-too-long? (> (.length uri) n) uri))
  (status [n ctx] n)

  java.util.Set
  (known-method? [set method]
    (contains? set method))
  (allowed-methods [s] s)
  (produces [set] set)

  clojure.lang.Keyword
  (known-method? [k method]
    (known-method? #{k} method))

  java.util.Map
  (body [m ctx]
    ;; Maps indicate keys are exact content-types
    ;; For matching on content-type, use a vector of vectors (TODO)
    (when-let [delegate (get m (get-in ctx [:response :content-type]))]
      (body delegate ctx)))
  (headers [m _] m)
  ;;(interpret-post-result [m _] m)
  (format-event [ev] )

  clojure.lang.PersistentVector
  (produces [v] v)
  (body [v ctx] v)
  (allowed-methods [v] v)

  java.util.List
  (allowed-methods [v] v)

  nil
  ;; These represent the handler defaults, all of which can be
  ;; overridden by providing non-nil arguments
  (service-available? [_ _] true)
  (request-uri-too-long? [_ uri]
    (request-uri-too-long? 4096 uri))
  #_(state [_ _] nil)
  (body [_ _] nil)
  (post [_ _] nil)
  (produces [_] nil)
  (status [_ _] nil)
  (headers [_ _] nil)
  (interpret-post-result [_ ctx] ctx)
  (allow-origin [_ _] nil)

  ReadPort
  #_(state [port ctx] (->source port))
  (body [port ctx] (->source port))

  Object
  (authorization [o] o)
  ;; Default is to return the value as-is and leave to subsequent
  ;; processing to determine how to manage or represent it
  #_(state [o ctx] o)

  )