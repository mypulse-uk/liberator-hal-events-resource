(ns liberator-hal-events-resource.events-resource-test
  (:require
    [clojure.string :as string]
    [clojure.test :refer [deftest is testing]]
    [halboy.json :as haljson]
    [halboy.resource :as hal]
    [liberator-hal-events-resource.events :as events]
    [liberator-hal-events-resource.events-resource :refer [build-events-resource EventsLoader]]
    [liberator-hal-events-resource.stubs.data :as data]
    [liberator-hal-events-resource.stubs.stubs :as stubs]
    [org.bovinegenius.exploding-fish :as uri]
    [ring.middleware.keyword-params :as keyword-params]
    [ring.middleware.params :as params]
    [ring.mock.request :as ring]))

(deftest events-resource-GET-on-success
  (let [routes [""
                [["/events" :events]]]
        pick-value 10
        event-id (str (random-uuid))
        event-1 (data/make-random-event {:id event-id})
        events-resource (-> (build-events-resource
                              {:routes routes}
                              pick-value
                              (stubs/->StubEventsLoader [event-1])
                              events/event->resource)
                            (keyword-params/wrap-keyword-params)
                            (params/wrap-params))
        result (stubs/call-resource
                 events-resource
                 (ring/request :get "/events"))
        resource (haljson/map->resource (:body result))]

    (testing "contains self link "
      (let [self-link (uri/uri (hal/get-href resource :self))
            {:strs [pick]} (uri/query-map self-link)]
        (is (= "/events" (uri/path self-link)))
        (is (= (str pick-value) pick))))

    (testing "contains next link"
      (let [next-link (uri/uri (hal/get-href resource :next))
            {:strs [pick since]} (uri/query-map next-link)]
        (is (= "/events" (uri/path next-link)))
        (is (= event-id since))
        (is (= (str pick-value) pick))))

    (testing "transform the event correctly"
      (is (= [event-id]
             (map #(:id (hal/get-property % :event))
                  (hal/get-resource resource :events)))))))

(deftest events-resource-GET-on-success-with-extra-query-params
  (let [routes [""
                [["/events" :events]]]
        number-value "123"
        collection-value  "[ \"a\", \"b\" ]"
        event-id (str (random-uuid))
        event-1 (data/make-random-event {:id event-id})
        events-resource (-> (build-events-resource
                              {:routes routes}
                              10
                              (stubs/->StubEventsLoader [event-1])
                              events/event->resource)
                            (keyword-params/wrap-keyword-params)
                            (params/wrap-params))
        result (stubs/call-resource
                 events-resource
                 (ring/request :get "/events" {:number number-value
                                               :collection collection-value}))
        resource (haljson/map->resource (:body result))]

    (testing "self link contains extra query params"
      (let [self-link (uri/uri (hal/get-href resource :self))
            {:strs [number collection]} (uri/query-map self-link)]
        (is (= number-value number))
        (is (= collection-value collection))))

    (testing "next link contains extra query params"
      (let [next-link (uri/uri (hal/get-href resource :next))
            {:strs [number collection]} (uri/query-map next-link)]
        (is (= number-value number))
        (is (= collection-value collection))))))

(deftest events-resource-GET-on-no-events-found
  (let [routes [""
                [["/events" :events]]]
        events-resource (-> (build-events-resource
                              {:routes routes} 10
                              (stubs/->StubEventsLoader [])
                              events/event->resource)
                            (keyword-params/wrap-keyword-params)
                            (params/wrap-params))
        result (stubs/call-resource
                 events-resource
                 (ring/request :get "/events"))
        resource (haljson/map->resource (:body result))]
    (testing "the list of event links is empty"
      (is (= [] (hal/get-link resource :events))))

    (testing "the list of embedded events is empty"
      (is (= [] (hal/get-resource resource :events))))))

(deftest events-resource-GET-on-events-found
  (let [routes [""
                [["/events" :events]]]
        first-event-id (str (random-uuid))
        second-event-id (str (random-uuid))
        third-event-id (str (random-uuid))
        event-1 (data/make-random-event {:id first-event-id})
        event-2 (data/make-random-event {:id second-event-id})
        event-3 (data/make-random-event {:id third-event-id})
        events-resource (-> (build-events-resource
                              {:routes routes} 10
                              (stubs/->StubEventsLoader [event-1
                                                         event-2
                                                         event-3])
                              events/event->resource)
                            (keyword-params/wrap-keyword-params)
                            (params/wrap-params))
        result (stubs/call-resource
                 events-resource
                 (ring/request :get "/events"))
        resource (haljson/map->resource (:body result))
        events (hal/get-resource resource :events)]

    (testing "returns links to those events"
      (is (= [first-event-id second-event-id third-event-id]
             (map #(-> %
                       (hal/get-property :event)
                       :id) events))))))

(deftest events-resource-GET-on-page-size-specified
  (let [routes [""
                [["/events" :events]]]
        first-event-id (str (random-uuid))
        second-event-id (str (random-uuid))
        third-event-id (str (random-uuid))
        event-1 (data/make-random-event {:id first-event-id})
        event-2 (data/make-random-event {:id second-event-id})
        event-3 (data/make-random-event {:id third-event-id})
        events-resource (-> (build-events-resource
                              {:routes routes} 10
                              (stubs/->StubEventsLoader [event-1
                                                         event-2
                                                         event-3])
                              events/event->resource)
                            (keyword-params/wrap-keyword-params)
                            (params/wrap-params))
        page-size 2
        first-result (stubs/call-resource
                       events-resource
                       (ring/request :get "/events" {:pick page-size}))
        first-resource (haljson/map->resource (:body first-result))
        first-page (hal/get-resource first-resource :events)

        next-href (hal/get-href first-resource :next)

        second-result (stubs/call-resource
                        events-resource
                        (ring/request :get next-href))
        second-page (haljson/map->resource (:body second-result))]
    (testing "returns ids to those events"
      (is (= [first-event-id second-event-id]
             (->>
               first-page
               (map #(hal/get-property % :event))
               (map :id)))))

    (testing "provides a next link which goes to the next page"
      (let [event-resources (->
                              second-page
                              (hal/get-resource :events))
            event-ids (->> event-resources
                           (map #(:id (hal/get-property % :event))))]
        (is (= [third-event-id] event-ids))))))

(deftest events-resource-GET-on-order-specified
  (let [routes [""
                [["/events" :events]]]
        first-event-id (str (random-uuid))
        second-event-id (str (random-uuid))
        third-event-id (str (random-uuid))
        event-1 (data/make-random-event {:id first-event-id})
        event-2 (data/make-random-event {:id second-event-id})
        event-3 (data/make-random-event {:id third-event-id})
        events-resource (-> (build-events-resource
                              {:routes routes}
                              10
                              (stubs/->StubEventsLoader [event-1
                                                         event-2
                                                         event-3])
                              events/event->resource)
                            (keyword-params/wrap-keyword-params)
                            (params/wrap-params))
        first-result (stubs/call-resource
                       events-resource
                       (ring/request :get "/events" {:order "DESC"}))

        resource (haljson/map->resource (:body first-result))
        page (hal/get-resource resource :events)]

    (testing "returns ids to those events"
      (is (= [third-event-id second-event-id first-event-id]
             (->>
               page
               (map #(hal/get-property % :event))
               (map :id)))))))

(deftest events-resource-GET-on-last-page
  (let [routes [["/events" :events]]
        first-event-id (str (random-uuid))
        event-1 (data/make-random-event {:id first-event-id})
        events-resource (-> (build-events-resource
                              {:routes routes} 10
                              (stubs/->StubEventsLoader [event-1])
                              events/event->resource)
                            (keyword-params/wrap-keyword-params)
                            (params/wrap-params))
        page-size 1

        first-result (stubs/call-resource
                       events-resource
                       (ring/request :get "/events" {:pick page-size}))
        first-resource (haljson/map->resource (:body first-result))

        next-href (hal/get-href first-resource :next)

        second-result (stubs/call-resource
                        events-resource
                        (ring/request :get next-href))
        second-page (haljson/map->resource (:body second-result))]
    (testing "final page has no events"
      (is (empty? (hal/get-resource second-page :events)))
      (is (empty? (hal/get-link second-page :events))))))

(deftest events-resource-GET-on-success-with-different-route
  (let [routes [""
                [["/other-events" :other-events]]]
        event-1 (data/make-random-event)
        event-2 (data/make-random-event)
        events-resource (-> (build-events-resource
                              {:routes routes}
                              1
                              (stubs/->StubEventsLoader [event-1 event-2])
                              events/event->resource
                              {:route-key :other-events})
                            (keyword-params/wrap-keyword-params)
                            (params/wrap-params))
        result (stubs/call-resource
                 events-resource
                 (ring/request :get "/other-events"))
        resource (haljson/map->resource (:body result))]

    (testing "self link contain alternative route"
      (is (string/includes? (hal/get-href resource :self)
                            "/other-events")))

    (testing "next link contain alternative route"
      (is (string/includes? (hal/get-href resource :next)
                            "/other-events")))))

(defrecord StreamFilteringEventsLoader [events]
           EventsLoader
           (load-events [_ {:keys [stream]}]
             (filter
               (fn [event] (= stream (-> event :payload :stream)))
               events)))

(deftest events-resource-GET-on-success-when-loading-events-with-extra-query-param
  (let [routes [["/events" :events]]
        event-1 (data/make-random-event {:payload {:stream "stream-1"}})
        event-2 (data/make-random-event {:payload {:stream "stream-2"}})
        event-3 (data/make-random-event {:payload {:stream "stream-3"}})
        events [event-1 event-2 event-3]

        events-resource (-> (build-events-resource
                              {:routes routes}
                              10
                              (->StreamFilteringEventsLoader events)
                              events/event->resource)
                            (keyword-params/wrap-keyword-params)
                            (params/wrap-params))

        first-result (stubs/call-resource
                       events-resource
                       (ring/request :get "/events" {:stream "stream-1"}))
        resource (haljson/map->resource (:body first-result))
        events (hal/get-resource resource :events)]

    (testing "has one event"
      (is (= 1 (count events))))

    (testing "event is event with matching stream"
      (is (= "stream-1" (-> events (first)
                            (hal/get-property :event)
                            (-> :payload :stream)))))))

(deftest events-resource-adds-liberator-overrides-if-provided
  (let [routes ["" [["/events" :events]]]
        events-resource
        (-> (build-events-resource
              {:routes routes}
              10
              (stubs/->StubEventsLoader [])
              events/event->resource
              {:overrides {:handle-ok (fn [_] {:override "test test"})}})
            (keyword-params/wrap-keyword-params)
            (params/wrap-params))

        result (stubs/call-resource
                 events-resource
                 (ring/request :get "/events"))

        resource (haljson/map->resource (:body result))]

    (testing "the override is used"
      (is (= "test test" (hal/get-property resource :override))))))

(defrecord ContextTestingEventsLoader [events]
           EventsLoader
           (load-events [_ context]
             (filter
               (fn [event] (= (get-in context [:request :uri]) (-> event :payload :uri)))
               events)))

(deftest events-loader-passes-liberator-context
  (let [routes [["/events" :events]]
        event-1 (data/make-random-event {:payload {:uri "/not-events"}})
        event-2 (data/make-random-event {:payload {:uri "/events"}})
        event-3 (data/make-random-event {:payload {:uri "/not-events"}})
        events [event-1 event-2 event-3]

        events-resource (-> (build-events-resource
                              {:routes routes}
                              10
                              (->ContextTestingEventsLoader events)
                              events/event->resource)
                            (keyword-params/wrap-keyword-params)
                            (params/wrap-params))

        first-result (stubs/call-resource
                       events-resource
                       (ring/request :get "/events" {:stream "stream-1"}))
        resource (haljson/map->resource (:body first-result))
        events (hal/get-resource resource :events)]

    (testing "has one event"
      (is (= 1 (count events))))

    (testing "event is event with matching context from request"
      (is (= "/events" (-> events (first)
                           (hal/get-property :event)
                           (-> :payload :uri)))))))
