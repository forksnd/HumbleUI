(in-ns 'io.github.humbleui.ui)

(require
  '[io.github.humbleui.clipboard :as clipboard])

(import
  '[io.github.humbleui.jwm TextInputClient]
  '[io.github.humbleui.skija BreakIterator Canvas Font FontMetrics TextLine]
  '[io.github.humbleui.skija.shaper ShapingOptions])
  
;; https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/EventOverview/TextDefaultsBindings/TextDefaultsBindings.html
;; https://github.com/davidbalbert/KeyBinding-Inspector

(def undo-stack-depth
  100)

(defn- char-iter ^BreakIterator [state]
  (or (:char-iter state)
    (doto (BreakIterator/makeCharacterInstance)
      (.setText (:text state)))))

(defn- word-iter ^BreakIterator [state]
  (or (:word-iter state)
    (doto (BreakIterator/makeWordInstance)
      (.setText (:text state)))))

(defn- preceding-word [^BreakIterator word-iter text pos]
  (let [pos' (.preceding word-iter pos)]
    (util/cond+
      (= 0 pos')
      pos'
      
      :do (.next word-iter)
      
      (not (util/between? (.getRuleStatus word-iter) BreakIterator/WORD_NONE BreakIterator/WORD_NONE_LIMIT))
      pos'
      
      :else
      (recur word-iter text pos'))))

(defn- following-word [^BreakIterator word-iter text pos]
  (let [pos' (.following word-iter pos)]
    (cond
      (= (count text) pos')
      pos'
      
      (not (util/between? (.getRuleStatus word-iter) BreakIterator/WORD_NONE BreakIterator/WORD_NONE_LIMIT))
      pos'
      
      :else
      (recur word-iter text pos'))))

(defmulti -edit
  (fn [_state command _arg]
    command))

(defmethod -edit :kill-marked [{:keys [text from to marked-from marked-to] :as state} _ _]
  (if (and marked-from marked-to)
    (let [text' (str (subs text 0 marked-from) (subs text marked-to))
          from' (cond
                  (<= from marked-from) from
                  (<= from marked-to)   marked-from
                  :else                 (- from (- marked-to marked-from)))
          to'   (cond
                  (= from to)         from'
                  (<= to marked-from) to
                  (<= to marked-to)   marked-from
                  :else                 (- to (- marked-to marked-from)))]
      (assoc state
        :text        text'
        :from        from'   
        :to          to'
        :marked-from nil
        :marked-to   nil))
    state))

(defmethod -edit :insert [{:keys [text from to] :as state} _ s]
  (assert (= from to))
  (assoc state
    :text (str (subs text 0 to) s (subs text to))
    :from (+ to (count s))
    :to   (+ to (count s))))

(defmethod -edit :insert-marked [{:keys [text from to] :as state} _ {s :text left :selection-start right :selection-end}]
  (assert (= from to))
  (assoc state
    :text        (str (subs text 0 to) s (subs text to))
    :from        (+ to left)
    :to          (+ to right)
    :marked-from to
    :marked-to   (+ to (count s))))

(defmethod -edit :move-char-left [{:keys [from to] :as state} _ _]
  (cond
    (not= from to)
    (assoc state
      :from (min from to)
      :to   (min from to))
    
    (= 0 to)
    state
    
    :else
    (let [char-iter (char-iter state)
          to'       (.preceding char-iter to)]
      (assoc state
        :from      to'
        :to        to'
        :char-iter char-iter))))

(defmethod -edit :move-char-right [{:keys [text from to] :as state} _ _]
  (cond
    (not= from to)
    (assoc state
      :from (max from to)
      :to   (max from to))
    
    (= (count text) to)
    state
    
    :else
    (let [char-iter (char-iter state)
          to'       (.following char-iter to)]
      (assoc state
        :from      to'
        :to        to'
        :char-iter char-iter))))

(defmethod -edit :move-word-left [{:keys [text from to] :as state} _ _]
  (cond
    (not= from to)
    (recur
      (assoc state
        :from (min from to)
        :to   (min from to))
      :move-word-left nil)
    
    (= 0 to)
    state
    
    :else
    (let [word-iter (word-iter state)
          to'       (preceding-word word-iter text to)]
      (assoc state
        :from      to'
        :to        to'
        :word-iter word-iter))))

(defmethod -edit :move-word-right [{:keys [text from to] :as state} _ _]
  (cond
    (not= from to)
    (recur
      (assoc state
        :from (max from to)
        :to   (max from to))
      :move-word-right nil)
    
    (= (count text) to)
    state
    
    :else
    (let [word-iter (word-iter state)
          to'       (following-word word-iter text to)]
      (assoc state
        :from      to'
        :to        to'
        :word-iter word-iter))))

(defmethod -edit :move-doc-start [state _ _]
  (assoc state
    :from 0
    :to   0))

(defmethod -edit :move-doc-end [{:keys [text] :as state} _ _]
  (assoc state
    :from (count text)
    :to   (count text)))

(defmethod -edit :move-to-position [state _ pos']
  (assert (<= 0 pos' (count (:text state))))
  (assoc state
    :from pos'
    :to   pos'))

(defmethod -edit :expand-char-left [{:keys [to] :as state} _ _]
  (cond
    (= to 0)
    state
    
    :else
    (let [char-iter (char-iter state)
          to'       (.preceding char-iter to)]
      (assoc state
        :to        to'
        :char-iter char-iter))))

(defmethod -edit :expand-char-right [{:keys [text to] :as state} _ _]
  (cond
    (= (count text) to)
    state
    
    :else
    (let [char-iter (char-iter state)
          to'       (.following char-iter to)]
      (assoc state
        :to        to'
        :char-iter char-iter))))

(defmethod -edit :expand-word-left [{:keys [text to] :as state} _ _]
  (cond
    (= to 0)
    state
    
    :else
    (let [word-iter (word-iter state)
          to'       (preceding-word word-iter text to)]
      (assoc state
        :to        to'
        :word-iter word-iter))))

(defmethod -edit :expand-word-right [{:keys [text to] :as state} _ _]
  (cond
    (= (count text) to)
    state
    
    :else
    (let [word-iter (word-iter state)
          to'       (following-word word-iter text to)]
      (assoc state
        :to        to'
        :word-iter word-iter))))

(defmethod -edit :expand-doc-start [{:keys [from to] :as state} _ _]
  (assoc state
    :from (if (= 0 from) 0 (max from to))
    :to   0))

(defmethod -edit :expand-doc-end [{:keys [text from to] :as state} _ _]
  (assoc state
    :from (if (= (count text) from) (count text) (min from to))
    :to   (count text)))

(defmethod -edit :expand-to-position [state _ pos']
  (assert (<= 0 pos' (count (:text state))))
  (assoc state
    :to pos'))

(defmethod -edit :delete-char-left [{:keys [text from to] :as state} _ _]
  (assert (= from to))
  (if (> to 0)
    (let [char-iter (char-iter state)
          to'       (.preceding char-iter to)]
      (assoc state
        :text (str (subs text 0 to') (subs text to))
        :from to'
        :to   to'))
    state))

(defmethod -edit :delete-char-right [{:keys [text from to] :as state} _ _]
  (assert (= from to))
  (if (< to (count text))
    (let [char-iter (char-iter state)
          to'       (.following char-iter to)]
      (assoc state
        :text (str (subs text 0 to) (subs text to'))
        :from to
        :to   to))
    state))

(defmethod -edit :delete-word-left [{:keys [text from to] :as state} _ _]
  (assert (= from to))
  (cond
    (= 0 to)
    state
    
    :else
    (let [word-iter (word-iter state)
          to'       (preceding-word word-iter text to)]
      (assoc state
        :text (str (subs text 0 to') (subs text to))
        :from to'
        :to   to'))))

(defmethod -edit :delete-word-right [{:keys [text from to] :as state} _ _]
  (assert (= from to))
  (cond
    (= (count text) to)
    state
    
    :else
    (let [word-iter (word-iter state)
          to'       (following-word word-iter text to)]
      (assoc state
        :text (str (subs text 0 to) (subs text to'))
        :from to
        :to   to))))

(defmethod -edit :delete-doc-start [{:keys [text from to] :as state} _ _]
  (assert (= from to))
  (if (> to 0)
    (assoc state
      :text (subs text to)
      :from 0
      :to   0)
    state))

(defmethod -edit :delete-doc-end [{:keys [text from to] :as state} _ _]
  (assert (= from to))
  (if (< to (count text))
    (assoc state
      :text (subs text 0 to)
      :from to
      :to   to)
    state))

(defmethod -edit :kill [{:keys [text from to] :as state} _ _]
  (assert (not= from to))
  (assoc state
    :text (str (subs text 0 (min from to)) (subs text (max from to)))
    :from (min from to)
    :to   (min from to)))

(defmethod -edit :transpose [{:keys [text from to] :as state} _ _]
  (assert (= from to))
  (cond
    (= to 0)
    state
    
    (< to (count text))
    (let [char-iter (char-iter state)
          preceding (.preceding char-iter to)
          following (.following char-iter to)]
      (assoc state
        :text (str
                (subs text 0 preceding)
                (subs text to following)
                (subs text preceding to)
                (subs text following))
        :from following
        :to   following))
    
    (= to (count text))
    (-> state
      (-edit :move-char-left nil)
      (-edit :transpose nil))))

(defmethod -edit :select-word [{:keys [text] :as state} _ pos']
  (assert (<= 0 pos' (count text)))
  (let [word-iter (word-iter state)
        last? (= pos' (count text))
        from' (cond
                last?
                (.preceding word-iter pos')
                
                (.isBoundary word-iter pos')
                pos'
                
                :else
                (.preceding word-iter pos'))
        to'   (if last?
                pos'
                (.following word-iter pos'))]
    (assoc state
      :from (if (= BreakIterator/DONE from') 0 from')
      :to   (if (= BreakIterator/DONE to') (count text) to'))))

(defmethod -edit :select-all [{:keys [text] :as state} _ _]
  (assoc state
    :from 0
    :to   (count text)))

(defmethod -edit :copy [{:keys [text from to] :as state} _ _]
  (assert (not= from to))
  (clipboard/set {:format :text/plain :text (subs text (min from to) (max from to))})
  state)  

(defmethod -edit :paste [{:keys [text from to] :as state} _ _]
  (assert (= from to))
  (when-some [{paste :text} (clipboard/get :text/plain)]
    (assoc state
      :text (str (subs text 0 to) paste (subs text to))
      :from (+ to (count paste))
      :to   (+ to (count paste)))))

(defmethod -edit :undo [{:keys [undo redo] :as state} _ _]
  (if-some [state' (peek undo)]
    (-> state
      (merge state')
      (assoc
        :undo (pop undo)
        :redo (conj (or redo []) (select-keys state [:text :from :to :offset]))))
    state))

(defmethod -edit :redo [{:keys [undo redo] :as state} _ _]
  (if-some [state' (peek redo)]
    (-> state
      (merge state')
      (assoc
        :undo (conj (or undo []) (select-keys state [:text :from :to :offset]))
        :redo (pop redo)))
    state))

(defn edit [state command arg]
  (let [state'  (-edit state command arg)
        edited? (not= (:text state') (:text state))
        state'  (cond-> state'
                  (or edited? (not= (:to state') (:to state)))
                  (assoc
                    :coord-to           nil
                    :cursor-blink-pivot (util/now)))]
    (util/cond+
      (not edited?)
      state'
      
      (#{:undo :redo} command)
      state'

      :do
      (when-some [line ^TextLine (:line state')]
        (.close line))
      
      ;; kill anything that depends on text
      :let [marked?    (#{:insert-marked :kill-marked} command)
            skip-undo? (and
                         (= (:last-change-to state) (:to state))
                         (= (:last-change-cmd state) command))]
      
      :else
      (cond-> state'
        true
        (assoc
          :line      nil
          :word-iter nil
          :char-iter nil
          :redo      nil)
        
        (not marked?)
        (assoc
          :last-change-cmd command
          :last-change-to  (:to state')
          :marked-from     nil
          :marked-to       nil)
        
        (and (not marked?) (not skip-undo?))
        (update :undo util/conjv-limited (select-keys state [:text :from :to]) undo-stack-depth)))))

(defn- get-cached [text-field _ctx key-source key-source-cached key-derived fn]
  (let [{:keys [*state]} text-field
        state          @*state
        source         (state key-source)
        source-cached  (state key-source-cached)
        derived-cached (state key-derived)]
    (or
      (when (= source source-cached)
        derived-cached)
      (let [derived (fn source state)]
        (util/close derived-cached)
        (swap! *state assoc
          key-source-cached source
          key-derived       derived)
        derived))))

(defn- text-line ^TextLine [text-field ctx]
  (get-cached text-field ctx :text :cached/text :line
    (fn [text state]
      (.shapeLine shaper text (:font state) (:features text-field)))))

(defn- placeholder-line ^TextLine [text-field ctx]
  (get-cached text-field ctx :placeholder :cached/placeholder :line-placeholder
    (fn [placeholder state]
      (let [font (or 
                   (:hui.text-field/font-placeholder ctx)
                   (:font state))]
        (.shapeLine shaper placeholder font (:features text-field))))))

(defn- coord-to [text-field ctx]
  (get-cached text-field ctx :to :cached/to :coord-to
    (fn [to _state]
      (let [line (text-line text-field ctx)]
        (math/round (.getCoordAtOffset line to))))))

(defn- correct-offset! [text-field ctx]
  (let [{:keys [*state bounds]} text-field
        state  @*state
        {:keys [offset]} state
        {:keys [scale]
         :hui.text-field/keys [cursor-width padding-left padding-right]} ctx
        line          (text-line text-field ctx)
        coord-to      (coord-to text-field ctx)
        line-width    (.getWidth line)
        bounds-width  (:width bounds)
        padding-left  (* scale padding-left)
        padding-right (* scale (+ cursor-width padding-right))
        min-offset    (- padding-left)
        max-offset    (-> line-width
                        (+ padding-right)
                        (- bounds-width)
                        (max min-offset))]
    (if (or
          (nil? offset)
          (< (- coord-to offset) padding-left) ;; cursor overflow left
          (> (- coord-to offset) (- bounds-width padding-right)) ;; cursor overflow right
          (< offset min-offset)
          (> offset max-offset))
      (-> *state
        (swap! assoc
          :offset (-> (- coord-to (/ bounds-width 2))
                    (util/clamp min-offset max-offset)
                    (math/round)))
        :offset)
      offset)))

(defn- correct-ranges! [*state]
  (let [state @*state
        len   (count (:text state))]
    (doseq [key [:from :to :marked-from :marked-to]
            :let [v (key state)]
            :when (some? v)]
      (when (> (key state) len)
        (swap! *state assoc key len)))))

(util/deftype+ TextInput [*value
                          *state
                          ^ShapingOptions features]
  :extends ATerminalNode  
  protocols/IComponent
  (-measure-impl [this ctx cs]
    (let [{:keys                [scale]
           :hui.text-field/keys [cursor-width
                                 padding-left
                                 padding-top
                                 padding-right 
                                 padding-bottom]} ctx
          metrics (:metrics @*state)
          line    (text-line this ctx)]
      (util/ipoint
        (min
          (:width cs)
          (+ (* scale padding-left)
            (.getWidth line) 
            (* scale cursor-width)
            (* scale padding-right)))
        (+ (math/round (:cap-height metrics))
          (* scale padding-top)
          (* scale padding-bottom)))))
  
  ;       coord-to                        
  ; ├──────────────────┤                  
  ;          ┌───────────────────┐        
  ; ┌────────┼───────────────────┼───────┐
  ; │        │         │         │       │
  ; └────────┼───────────────────┼───────┘
  ;          └───────────────────┘        
  ; ├────────┼───────────────────┤        
  ;   offset     (:width bounds)          
  ;                                       
  ; ├────────────────────────────────────┤
  ;            (.getWidth line)           
  (-draw-impl [this ctx bounds container-size viewport ^Canvas canvas]
    (correct-ranges! *state)
    (correct-offset! this ctx)
    
    (let [state @*state
          {:keys [text from to marked-from marked-to offset metrics]} state
          {:keys                [scale]
           :hui/keys            [focused?]
           :hui.text-field/keys [padding-top]} ctx
          line       (text-line this ctx)
          cap-height (math/round (:cap-height metrics))
          ascent     (math/ceil (- (- (:ascent metrics)) (:cap-height metrics)))
          descent    (math/ceil (:descent metrics))
          baseline   (+ (* scale padding-top) cap-height)
          selection? (not= from to)
          coord-to   (coord-to this ctx)
          coord-from (if (= from to)
                       coord-to
                       (math/round (.getCoordAtOffset line from)))]
      (canvas/with-canvas canvas
        (canvas/clip-rect canvas (util/rect bounds))
        
        ;; selection
        (when selection?
          (with-paint ctx [paint (if focused?
                                   (:hui.text-field/fill-selection-active ctx)
                                   (:hui.text-field/fill-selection-inactive ctx))]
            (canvas/draw-rect canvas
              (util/rect-ltrb
                (+ (:x bounds) (- offset) (min coord-from coord-to))
                (+ (:y bounds) (* scale padding-top) (- ascent))
                (+ (:x bounds) (- offset) (max coord-from coord-to))
                (+ (:y bounds) baseline descent))
              paint)))
        
        ;; text
        (let [placeholder? (= "" text)
              line (if placeholder?
                     (placeholder-line this ctx)
                     line)
              x    (+ (:x bounds) (- offset))
              y    (+ (:y bounds) baseline)
              fill (if placeholder?
                     (:hui.text-field/fill-placeholder ctx)
                     (:hui.text-field/paint ctx))]
          (when line
            (when (.isClosed line)
              (util/log "(.isClosed line)" (.isClosed line) line))
            (with-paint ctx [paint fill]
              (.drawTextLine canvas line x y paint))))
        
        ;; composing region
        (when (and marked-from marked-to)
          (let [left  (.getCoordAtOffset line marked-from)
                right (.getCoordAtOffset line marked-to)]
            (with-paint ctx [paint (:hui.text-field/paint ctx)]
              (canvas/draw-rect canvas
                                (util/rect-ltrb
                                  (+ (:x bounds) (- offset) left)
                                  (+ (:y bounds) baseline (* 1 scale))
                                  (+ (:x bounds) (- offset) right)
                                  (+ (:y bounds) baseline (* 2 scale)))
                                paint))))
        
        ;; cursor
        (when focused?
          (let [now                   (util/now)
                cursor-width          (* scale (:hui.text-field/cursor-width ctx))
                cursor-left           (quot cursor-width 2)
                cursor-right          (- cursor-width cursor-left)
                cursor-blink-interval (:hui.text-field/cursor-blink-interval ctx)
                cursor-blink-pivot    (:cursor-blink-pivot state)
                cursor-visible?       (or
                                        (<= cursor-blink-interval 0)
                                        (<= (mod (- now cursor-blink-pivot) (* 2 cursor-blink-interval)) cursor-blink-interval))]
            (when (and cursor-visible? (not selection?))
              (with-paint ctx [paint (:hui.text-field/fill-cursor ctx)]
                (canvas/draw-rect canvas
                  (util/rect-ltrb
                    (+ (:x bounds) (- offset) coord-to (- cursor-left))
                    (+ (:y bounds) (* scale padding-top) (- ascent))
                    (+ (:x bounds) (- offset) coord-to cursor-right)
                    (+ (:y bounds) baseline descent))
                  paint)))
            (when (> cursor-blink-interval 0)
              (util/schedule
                #(window/request-frame (:window ctx))
                (- cursor-blink-interval
                  (mod (- now cursor-blink-pivot) cursor-blink-interval)))))))))
        
  (-event-impl [this ctx event]
    ; (when-not (#{:frame :frame-skija :window-focus-in :window-focus-out :mouse-move} (:event event))
    ;   (println (:hui/focused? ctx) event))
    (when (:hui/focused? ctx)
      (let [state @*state
            {:keys [from to marked-from marked-to offset ^TextLine line mouse-clicks last-mouse-click]} state]
        (when (= :mouse-move (:event event))
          (swap! *state assoc
            :mouse-clicks 0))

        (util/cond+
          ;; mouse down
          (and
            (= :mouse-button (:event event))
            (= :primary (:button event))
            (:pressed? event)
            bounds
            (util/rect-contains? bounds (util/ipoint (:x event) (:y event))))
          (let [x             (-> (:x event)
                                (- (:x bounds))
                                (+ offset))
                offset'       (.getOffsetAtCoord line x)
                now           (util/now)
                mouse-clicks' (if (<= (- now last-mouse-click) util/double-click-threshold-ms)
                                (inc mouse-clicks)
                                1)]
            (swap! *state #(cond-> %
                             true
                             (assoc
                               :selecting?       true
                               :last-mouse-click now
                               :mouse-clicks     mouse-clicks')
                         
                             (= 1 mouse-clicks')
                             (edit :move-to-position offset')
                         
                             (= 2 mouse-clicks')
                             (edit :select-word offset')
                          
                             (< 2 mouse-clicks')
                             (edit :select-all nil)))
            true)
          
          ; mouse up
          (and
            (= :mouse-button (:event event))
            (= :primary (:button event))
            (not (:pressed? event)))
          (do
            (swap! *state assoc :selecting? false)
            false)
          
          ;; mouse move
          (and
            (= :mouse-move (:event event))
            (:selecting? state))
          (let [x (-> (:x event)
                    (- (:x bounds))
                    (+ offset))]
            (cond
              (util/rect-contains? bounds (util/ipoint (:x event) (:y event)))
              (swap! *state edit :expand-to-position (.getOffsetAtCoord line x))
              
              (< (:y event) (:y bounds))
              (swap! *state edit :expand-to-position 0)
              
              (>= (:y event) (:bottom bounds))
              (swap! *state edit :expand-to-position (count (:text state))))
            true)
          
          ;; typing
          (= :text-input (:event event))
          (do
            (swap! *state
              (fn [state]
                (let [state (cond
                              ;; straight up replace part of text
                              (not (neg? (:replacement-start event)))
                              (assoc state
                                :from (:replacement-start event)
                                :to   (:replacement-end event))
                              
                              ;; replace marked with text
                              (:marked-from state)
                              (edit state :kill-marked nil)
                              
                              :else
                              state)
                      state (if (not= (:from state) (:to state))
                              (edit state :kill nil)
                              state)]
                  (edit state :insert (:text event)))))
            (try
              (invoke-callback this :on-change (:text @*state))
              (catch Throwable e
                (util/log-error e)))
            true)
          
          ;; composing region
          (= :text-input-marked (:event event))
          (do
            (swap! *state
              (fn [state]
                (let [state (if-not (neg? (:replacement-start event))
                              (-> state
                                (edit :move-to-position (:replacement-start event))
                                (edit :expand-to-position (:replacement-end event)))
                              state)
                      state (if (:marked-from state)
                              (edit state :kill-marked nil)
                              state)
                      state (if (not= (:from state) (:to state))
                              (edit state :kill nil)
                              state)]
                  (edit state :insert-marked event))))
            true)
          
          ;; text input client
          (= :get-text-input-client (:event event))
          {:client this
           :ctx    ctx}
          
          ;; emoji popup macOS
          (and
            (= :macos app/platform)
            (= :key (:event event))
            (:pressed? event)
            (= :space (:key event)) 
            ((:modifiers event) :mac-command)
            ((:modifiers event) :control))
          (do
            (app/open-symbols-palette)
            false)
                    
          ;; command
          (and (= :key (:event event)) (:pressed? event))
          (let [key        (:key event)
                shift?     ((:modifiers event) :shift)
                macos?     (= :macos app/platform)
                cmd?       ((:modifiers event) :mac-command)
                option?    ((:modifiers event) :mac-option)
                ctrl?      ((:modifiers event) :control)
                selection? (not= from to)
                ops        (or
                             (util/when-case (and macos? cmd? shift?) key
                               :left  [:expand-doc-start]
                               :right [:expand-doc-end]
                               :z     [:redo])
                             
                             (util/when-case (and macos? option? shift?) key
                               :left  [:expand-word-left]
                               :right [:expand-word-right])

                             (util/when-case shift? key
                               :left  [:expand-char-left]
                               :right [:expand-char-right]
                               :up    [:expand-doc-start]
                               :down  [:expand-doc-end]
                               :home  [:expand-doc-start]
                               :end   [:expand-doc-end])
                             
                             (util/when-case selection? key
                               :backspace [:kill]
                               :delete    [:kill])
                             
                             (util/when-case (and macos? cmd? selection?) key
                               :x         [:copy :kill]
                               :c         [:copy]
                               :v         [:kill :paste])
                               
                             (util/when-case (and macos? cmd?) key
                               :left      [:move-doc-start]
                               :right     [:move-doc-end]
                               :a         [:select-all]
                               :backspace [:delete-doc-start]
                               :delete    [:delete-doc-end]
                               :v         [:paste]
                               :z         [:undo])
                             
                             (util/when-case (and macos? option?) key
                               :left      [:move-word-left]
                               :right     [:move-word-right]
                               :backspace [:delete-word-left]
                               :delete    [:delete-word-right])
                             
                             (util/when-case (and macos? ctrl? option? shift?) key
                               :b [:expand-word-left]
                               :f [:expand-word-right])
                               
                             (util/when-case (and macos? ctrl? shift?) key
                               :b [:expand-char-left]
                               :f [:expand-char-right]
                               :a [:expand-doc-start]
                               :e [:expand-doc-end]
                               :p [:expand-doc-start]
                               :n [:expand-doc-end])
                             
                             (util/when-case (and macos? ctrl? selection?) key
                               :h [:kill]
                               :d [:kill])
                             
                             (util/when-case (and macos? ctrl? option?) key
                               :b [:move-word-left]
                               :f [:move-word-right])
                               
                             (util/when-case (and macos? ctrl?) key
                               :b [:move-char-left]
                               :f [:move-char-right]
                               :a [:move-doc-start]
                               :e [:move-doc-end]
                               :p [:move-doc-start]
                               :n [:move-doc-end]
                               :h [:delete-char-left]
                               :d [:delete-char-right]
                               :k [:delete-doc-end])
                             
                             (util/when-case (and macos? ctrl? (not selection?)) key
                               :t [:transpose])

                             (util/when-case (and (not macos?) shift? ctrl?) key
                               :z [:redo])
                             
                             (util/when-case (and (not macos?) ctrl?) key
                               :left [:move-word-left]
                               :right [:move-word-right]
                               :backspace [:delete-word-left]
                               :delete [:delete-word-right]
                               :a [:select-all]
                               :z [:undo]
                               :y [:redo])
                             
                             (util/when-case (and (not macos?) ctrl? selection?) key
                               :x [:copy :kill]
                               :c [:copy]
                               :v [:kill :paste])
                             
                             (util/when-case true key
                               :left      [:move-char-left]
                               :right     [:move-char-right]
                               :up        [:move-doc-start]
                               :down      [:move-doc-end]
                               :home      [:move-doc-start]
                               :end       [:move-doc-end]
                               :backspace [:kill-marked :delete-char-left]
                               :delete    [:kill-marked :delete-char-right]))]
            (or
              (when (seq ops)
                (swap! *state
                  (fn [state]
                    (reduce #(edit %1 %2 nil) state ops)))
                (invoke-callback this :on-change (:text @*state))
                true)
              (some #{:letter :digit :whitespace} (:key-types event))))
          
          (and (= :key (:event event)) (not (:pressed? event)))
          (some #{:letter :digit :whitespace} (:key-types event))))))
  
  TextInputClient
  (getRectForMarkedRange [this selection-start selection-end]
    (let [{:keys [from to marked-from marked-to offset metrics]} @*state
          {:hui.text-field/keys [padding-top]} util/*text-input-ctx*
          line       (text-line this util/*text-input-ctx*)
          cap-height (Math/ceil (:cap-height metrics))
          ascent     (Math/ceil (- (- (:ascent metrics)) cap-height))
          descent    (Math/ceil (:descent metrics))
          baseline   (+ padding-top cap-height)
          left       (.getCoordAtOffset line (or marked-from from))
          right      (if (= (or marked-to to) (or marked-from from))
                       left
                       (.getCoordAtOffset line (or marked-to to)))]
      (util/irect-ltrb
        (+ (:x bounds) (- offset) left)
        (+ (:y bounds) padding-top (- ascent))
        (+ (:x bounds) (- offset) right)
        (+ (:y bounds) baseline descent))))
  
  (getSelectedRange [_]
    (let [{:keys [from to]} @*state]
      (util/irange from to)))
  
  (getMarkedRange [_]
    (let [{:keys [marked-from marked-to]} @*state]
      (util/irange marked-from marked-to)))
  
  (getSubstring [_ start end]
    (let [{:keys [text]} @*state
          len (count text)
          start (min start len)
          end   (min end start)]
      (subs text start end))))

(defn text-input-ctor
  ([]
   (text-input-ctor {}))
  ([{:keys [*value *state on-change] :as opts}]
   (let [font     (get-font opts)
         metrics  (font/metrics font)
         features (:hui.text-field/font-features *ctx*)
         features (reduce #(.withFeatures ^ShapingOptions %1 ^String %2) ShapingOptions/DEFAULT features)
         *value   (or
                    (:*value opts)
                    (signal ""))
         *state   (or
                    (:*state opts)
                    (signal {}))]
     (swap! *value #(or % ""))
     (swap! *state #(util/merge-some
                      {:text               ""
                       :font               font
                       :metrics            metrics
                       :cached/text        nil
                       :line               nil
                         
                       :placeholder        ""
                       :cached/placeholder nil
                       :line-placeholder   nil
                         
                       :from               0
                       :to                 0
                       :cached/to          nil
                       :coord-to           nil
                         
                       :last-change-cmd    nil
                       :last-change-to     nil
                       :marked-from        nil
                       :marked-to          nil
                       :word-iter          nil
                       :char-iter          nil
                       :undo               nil
                       :redo               nil
                       :cursor-blink-pivot (util/now)
                       :offset             nil
                       :selecting?         false
                       :mouse-clicks       0
                       :last-mouse-click   0}
                      %))
     (map->TextInput
       {:*value *value
        :*state *state
        :features features}))))

(defn text-field-ctor
  ([]
   (text-field-ctor {}))
  ([opts]
   [focusable opts
    [on-key-focused
     opts
     [with-cursor {:cursor :ibeam}
      (let [active? (:focused? *ctx*)
            stroke  (if active?
                      (:hui.text-field/border-active *ctx*)
                      (:hui.text-field/border-inactive *ctx*))
            bg      (if active?
                      (:hui.text-field/fill-bg-active *ctx*)
                      (:hui.text-field/fill-bg-inactive *ctx*))
            radius  (:hui.text-field/border-radius *ctx*)]
        [rect {:radius radius, :paint bg}
         [rect {:radius radius, :paint stroke}
          [text-input-ctor opts]]])]]]))
