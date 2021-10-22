(ns athens.events.remote
  "`re-frame` events related to `:remote/*`."
  (:require
    [athens.common-db                     :as common-db]
    [athens.common-events.resolver.atomic :as atomic-resolver]
    [athens.common-events.schema          :as schema]
    [athens.common.logging                :as log]
    [athens.db                            :as db]
    [datascript.core                      :as d]
    [event-sync.core                      :as event-sync]
    [malli.core                           :as m]
    [malli.error                          :as me]
    [re-frame.core                        :as rf]))


;; Connection Management

(rf/reg-event-fx
  :remote/connect!
  (fn [_ [_ remote-db]]
    (log/info ":remote/connect!" (pr-str remote-db))
    {:remote/client-connect! remote-db
     :fx                     [[:dispatch [:conn-status :connecting]]]}))


(rf/reg-event-fx
  :remote/connected
  (fn [_ _]
    (log/info ":remote/connected")
    {:fx [[:dispatch [:conn-status :connected]]]}))


(rf/reg-event-fx
  :remote/connection-failed
  (fn [_ _]
    (log/warn ":remote/connection-failed")
    {:fx [[:dispatch-n [[:alert/js "Was not able to connect to the remote database."]
                        [:conn-status :disconnected]]]]}))


(rf/reg-event-fx
  :remote/disconnect!
  (fn [_ _]
    {:remote/client-disconnect!   nil
     :remote/clear-dsdb-snapshot! nil
     :dispatch                    [:remote/stop-event-sync]}))


(rf/reg-event-db
  :remote/updated-last-seen-tx
  (fn [db _]
    ;; TODO(RTC): clean this up, we don't need last seen TX no mo
    (log/debug ":remote/updated-last-seen-tx")
    db))


(rf/reg-event-fx
  :remote/last-seen-tx!
  (fn [{db :db} [_ new-tx-id]]
    (log/debug "last-seen-tx!" new-tx-id)
    {:db (assoc db :remote/last-seen-tx new-tx-id)
     :fx [[:dispatch [:remote/updated-last-seen-tx]]]}))


;; Send it

(rf/reg-event-fx
  :remote/send-event!
  (fn [_ [_ event]]
    (if (schema/valid-event? event)
      ;; valid event, send item
      {:remote/send-event-fx! event}
      (let [explanation (-> schema/event
                            (m/explain event)
                            (me/humanize))]
        ;; TODO display alert?
        (log/warn "Not sending invalid event. Error:" (pr-str explanation)
                  "\nInvalid event was:" (pr-str event))))))


;; Remote graph related events


(rf/reg-fx
  :remote/clear-dsdb-snapshot!
  (fn []
    (reset! db/dsdb-snapshot nil)))


(rf/reg-event-fx
  :remote/snapshot-dsdb
  (fn [_ _]
    {:remote/snapshot-dsdb! nil}))


(rf/reg-fx
  :remote/snapshot-dsdb!
  (fn []
    (log/debug ":remote/snapshot-dsdb! at time" (:max-tx @db/dsdb))
    (reset! db/dsdb-snapshot @db/dsdb)))


(rf/reg-event-fx
  :remote/rollback-dsdb
  (fn [_ _]
    (log/debug ":remote/rollback-dsdb to time" (:max-tx @db/dsdb-snapshot))
    {:reset-conn! @db/dsdb-snapshot}))


;; NB: this operation needs to perform all these stateful operations one after another
;; and can't be split, so we can't reuse some of the other events we have.
(rf/reg-event-fx
  :remote/rollback-resolve-transact-snapshot
  (fn [_ [_ event]]
    (log/debug ":remote/rollback-resolve-transact-snapshot rollback db to time" (:max-tx @db/dsdb-snapshot))
    (d/reset-conn! db/dsdb @db/dsdb-snapshot)
    (let [txs (atomic-resolver/resolve-to-tx @db/dsdb-snapshot event)]
      (log/debug ":remote/rollback-resolve-transact-snapshot resolved txs:" (pr-str txs))
      (common-db/transact-with-middleware! db/dsdb txs))
    (log/debug ":remote/rollback-resolve-transact-snapshot snapshot at time" (:max-tx @db/dsdb))
    (reset! db/dsdb-snapshot @db/dsdb)
    {}))


(rf/reg-event-db
  :remote/start-event-sync
  (fn [db _]
    (assoc db :event-sync (event-sync/create-state :athens [:memory :server]))))


(rf/reg-event-db
  :remote/stop-event-sync
  (fn [db _]
    (dissoc db :event-sync)))


(rf/reg-event-fx
  :remote/clear-server-event
  (fn [{db :db} [_ event]]
    {:db (update db :event-sync #(event-sync/remove % :server (:event/id event) event))}))


(defn- new-event?
  [[type _ _ _ noop?]]
  (and (= type :add) (not noop?)))


(rf/reg-event-fx
  :remote/snapshot-transact
  (fn [_ [_ event]]
    (log/debug ":remote/snapshot-transact update to time" (inc (:max-tx @db/dsdb-snapshot)))
    {:remote/snapshot-transact! (atomic-resolver/resolve-to-tx @db/dsdb-snapshot event)}))


(rf/reg-fx
  :remote/snapshot-transact!
  (fn [tx-data]
    (swap! db/dsdb-snapshot
           (fn [db]
             (d/db-with db (common-db/tx-with-middleware db tx-data))))))


(rf/reg-event-fx
  :remote/reject-forwarded-event
  (fn [{db :db} [_ {:event/keys [id] :as event}]]
    (log/debug ":remote/reject-forwarded-event event:" (pr-str event))
    (let [db'            (update db :event-sync #(event-sync/remove % :memory id event))
          memory-log     (event-sync/stage-log (:event-sync db') :memory)]
      {:db db'
       ;; Rollback and reapply all events in the memory stage.
       ;; If there's no events to reapply, just mark as synced.
       :fx [[:dispatch-n (into [[:remote/rollback-dsdb]]
                               (if (empty? memory-log)
                                 [[:db/sync]]
                                 (map (fn [e] [:resolve-transact (second e)])
                                      (-> db' :event-sync :stages :memory))))]]})))


(rf/reg-event-fx
  :remote/apply-forwarded-event
  (fn [{db :db} [_ {:event/keys [id] :as event}]]
    (log/debug ":remote/apply-forwarded-event event:" (pr-str event))
    (let [db'        (update db :event-sync #(event-sync/add % :server id event))
          new-event? (new-event? (-> db' :event-sync :last-op))
          memory-log (event-sync/stage-log (:event-sync db') :memory)]
      (log/debug ":remote/apply-forwarded-event new event?:" new-event?)
      {:db db'
       :fx [[:dispatch-n (cond-> []
                           ;; Mark as synced if there's no events left in memory.
                           (empty? memory-log)  (into [[:db/sync]])
                           ;; If no new event was added, just update the snapshot with event.
                           (not new-event?) (into [[:remote/snapshot-transact event]])
                           ;; If there's a new event, apply it over the last dsdb snapshot from
                           ;; the server, then use that as the new snapshot, then reapply
                           ;; all events in the memory stage.
                           ;; NB: would be more performant to just transact over dsdb and dsdb-snapshot
                           ;; if there's no txs in-memory to reapply, but this is ok for now.
                           new-event?       (into [[:remote/rollback-resolve-transact-snapshot event]])
                           new-event?       (into (map (fn [e] [:resolve-transact (second e)])
                                                       (-> db' :event-sync :stages :memory)))
                           ;; Remove the server event after everything is done.
                           true                 (into [[:remote/clear-server-event event]]))]]})))


(rf/reg-event-fx
  :remote/forward-event
  (fn [{db :db} [_ {:event/keys [id] :as event}]]
    (log/debug ":remote/forward-event event:" (pr-str event))
    {:db (update db :event-sync #(event-sync/add % :memory id event))
     :fx [[:dispatch-n [[:remote/send-event! event]
                        [:db/not-synced]]]]}))
