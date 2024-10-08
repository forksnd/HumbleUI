(ns io.github.humbleui.docs.size
  (:require
    [io.github.humbleui.docs.shared :as shared]
    [io.github.humbleui.util :as util]
    [io.github.humbleui.signal :as signal]
    [io.github.humbleui.ui :as ui]))

(ui/defcomp ui []
  (let [fill {:fill 0x80FFDB2C}]
    (fn []
      (shared/table
        "Force child’s width"
        [ui/size {:width 50}
         [ui/rect {:paint fill}
          [ui/align {:x :center}
           "abc"]]]
    
        "Force child’s height"
        [ui/size {:height 50}
         [ui/rect {:paint fill}
          [ui/align {:y :center}
           "abc"]]]
      
        "Force both width and height"
        [ui/size {:width 50 :height 50}
         [ui/rect {:paint fill}
          [ui/center
           "abc"]]]
        
        "Shorthand for when width == height"
        [ui/size {:size 50}
         [ui/rect {:paint fill}
          [ui/center
           "abc"]]]
         
        "Does not grow on overflow"
        [ui/size {:width 10 :height 20}
         [ui/rect {:paint fill}
          [ui/center
           "abc"]]]
         
        "Accepts functions of parent size"
        [ui/size {:width (fn [cs]
                           (* 0.25 (:width cs)))}
         [ui/rect {:paint fill}
          "abc"]]))))