(ns chocolatier.engine.systems.collision
  "System for checking collisions between entities"
  (:require [chocolatier.utils.logging :as log]
            [chocolatier.engine.ecs :as ecs]
            [chocolatier.engine.events :as ev]
            [chocolatier.engine.rtree :as r]))


(def tilemap-spatial-index-location
  [:state :spatial-index-map])

(def entity-spatial-index-location
  [:state :spatial-index-entity])

(defn entity-state->bounding-box
  "Format entity state for use with spatial index. Represents where the entity
   will be.

   Args:
   - id: The unique ID of the entity
   - position: The position state of the entity
   - collision-state: The collideable state of the entity"
  [entity-id
   {:keys [screen-x screen-y]}
   {:keys [height width attributes]}]
  (r/rtree-item screen-x
                screen-y
                (+ screen-x width)
                (+ screen-y height)
                ;; Include the parent ID so we can pass information
                ;; about who created this collidable entity
                (assoc attributes :id entity-id)))

(defn index-entities!
  "Returns a spatial index with all elegible entities inserted."
  [state index entity-ids]
  ;; Always clear out before inserting items to avoid duplication
  (r/rtree-clear! index)
  (let [collidable-states (ecs/get-all-component-state state :collidable)
        position-states (ecs/get-all-component-state state :position)]
    (loop [entities entity-ids
           items (array)]
      (let [entity-id (first entities)]
        (if (nil? entity-id)
          (r/rtree-bulk-insert! index items)
          (let [position-state (get position-states entity-id)
                collision-state (get collidable-states entity-id)]
            (when (not (and (nil? position-state) (nil? collision-state)))
              (.push items (entity-state->bounding-box entity-id
                                                       position-state
                                                       collision-state)))
            (recur (rest entities) items)))))))

(defn get-or-create-entity-spatial-index
  [state max-entries]
  (get-in state entity-spatial-index-location (r/rtree max-entries)))

(def collision-queue-path
  (conj ev/queue-path :collision))

(defn attack?
  "Returns a boolean of whether the id is an attack"
  [attributes]
  (contains? attributes :damage))

(defn valid-entity-collision-item?
  "Returns true if the spatial index item, a four element js
   array, is valid for checking collisions against.

   Excludes:
   - Items that are not in the view port (via width and height)
   - Items that are an attack (attacks shouldn't collide with attacks)
   - Items with a from ID that is the same as the entity-id (immune to your
     own attacks)"
  [[x y _ _ {:keys [id from-id damage]}] width height]
  (and (< x width)
       (< y height)
       (not damage)
       (not (keyword-identical? id from-id))))

(defn self? [id attributes]
  (keyword-identical? id (get attributes :id)))

(defn self-attack? [id attributes]
  (keyword-identical? id (get attributes :from-id)))

(defn entity-collision-events
  "Returns a hashmap of all collision events by entity ID"
  [collision-items spatial-index width height]
  (loop [items collision-items
         accum (transient {})]
    (let [item (first items)]
      (if item
        (if (valid-entity-collision-item? item width height)
          (let [id (:id (aget item 4))
                collisions (r/rtree-search spatial-index item)]
            (if (some (fn [[_ _ _ _ attributes]]
                        (not (or (self? id attributes)
                                 (self-attack? id attributes))))
                      collisions)
              (recur (rest items)
                     (assoc! accum id [(ev/mk-event {:collisions collisions}
                                                    [:collision id])]))
              (recur (rest items) accum)))
          (recur (rest items) accum))
        (persistent! accum)))))

(defn mk-entity-collision-system
  "Returns a function parameterized by the height, width, of the view port and
   max entries for the R-tree.

   Returns an update game state with collision events emitted for all eligible
   entities stored in the spatial index"
  [height width max-entries]
  (fn [state]
    (let [entity-ids (ecs/entities-with-component state :collidable)
          spatial-index (get-or-create-entity-spatial-index state max-entries)
          spatial-index (index-entities! state spatial-index entity-ids)
          items (r/rtree-all spatial-index)
          events (entity-collision-events items spatial-index width height)
          next-state (assoc-in state entity-spatial-index-location spatial-index)]
      (ev/batch-emit-events next-state [:collision] events))))

(defn get-or-create-tilemap-spatial-index
  [state max-entries]
  (get-in state tilemap-spatial-index-location (r/rtree max-entries)))

(defn index-tilemap!
  "Returns the index with all tiles in the map inserted"
  [state index tiles]
  (loop [tiles tiles
         items (array)]
    (let [tile (first tiles)]
      (if (nil? tile)
        (r/rtree-bulk-insert! index items)
        (let [{:keys [screen-x screen-y width height attributes]} tile]
          (when (:impassable? attributes)
            (.push items (r/rtree-item screen-x screen-y
                                       (+ screen-x width)
                                       (+ screen-y height)
                                       attributes)))
          (recur (rest tiles) items))))))

(defn tilemap-collision-events
  "Returns a hashmap of all collision events with the tilemap"
  [index position-states collidable-states entity-ids]
  (loop [entities entity-ids
         accum (transient {})]
    (let [entity-id (first entities)]
      (if (nil? entity-id)
        (persistent! accum)
        (let [position-state (get position-states entity-id)
              collision-state (get collidable-states entity-id)
              item (entity-state->bounding-box entity-id
                                               position-state
                                               collision-state)
              collisions (r/rtree-search index item)]
          (if (seq collisions)
            ;; Ignore items that have a damage item
            (if (attack? (aget item 4))
              (recur (rest entities) accum)
              (recur (rest entities)
                     (assoc! accum entity-id
                             [(ev/mk-event {:collisions collisions}
                                           [:collision entity-id])])))
            (recur (rest entities) accum)))))))

(defn mk-tilemap-collision-system
  [height width max-entries]
  ;; HACK for now since tilemaps are static, only index it once
  (let [refresh? (atom true)]
    (fn [state]
      (let [entity-ids (ecs/entities-with-component state :collidable)
            tiles (get-in state [:state :tiles])
            spatial-index (get-or-create-tilemap-spatial-index state max-entries)
            spatial-index (if @refresh?
                            (do (reset! refresh? false)
                                (index-tilemap! state spatial-index tiles))
                            spatial-index)
            position-states (ecs/get-all-component-state state :position)
            collidable-states (ecs/get-all-component-state state :collidable)
            events (tilemap-collision-events spatial-index
                                             position-states
                                             collidable-states
                                             entity-ids)]
        (-> state
            (assoc-in tilemap-spatial-index-location spatial-index)
            ;; Emit all of the collision events in one shot
            ;; FIX will this overwrite all collision events?
            (ev/batch-emit-events [:collision] events))))))
