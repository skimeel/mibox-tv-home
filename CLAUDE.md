# CLAUDE.md

## Design principles

Follow these for every UI change to the launcher. It is a TV seen from about 3 metres, running on a
2 GB Mi Box S with a weak GPU.

1. **Layout:** keep a 48 dp safe margin on all sides. Use an 8 dp spacing grid. Cards use a 16 dp
   corner radius; pills and icon buttons are fully round.
2. **Type:** body text 24 sp or larger, secondary text never below 16 sp, one typeface, at most
   three sizes on a screen. Everything must be readable from the couch.
3. **Colour:** dark backgrounds, one accent colour, text contrast of at least 4.5:1. Nothing relies
   on colour alone.
4. **Focus:** the focused item is always unmistakable. Scale about 1.08 with a clear contrast
   change, 150 to 200 ms ease-out. Unfocused items stay calm.
5. **Motion:** short and purposeful, 150 to 250 ms. Nothing animates while idle. Exception: the
   slow wallpaper crossfade every 10 minutes stays, because it is a core part of the look. Do not
   remove or shorten it. Exception: the 1.5 s night fade, when night mode dims the photo or lifts the
   dimming at the minute tick, stays slow so the change is gentle and not a sudden jump.
   Exception: the screensaver fades each photo out to black and the next one in (about 1 s each)
   every dream_photo_seconds, and fades the clock out and back in (1 s each) when it moves every
   dream_clock_move_minutes. Nothing else in it moves.
6. **Cost:** no blur or RenderScript, no big shadows on many views, no full-screen overdraw. Keep
   total launcher memory under 40 MB, including the screensaver while it runs. It holds one
   full-size 1920x1080 ARGB_8888 photo (about 8 MB) at a time, never two, which is why it fades
   through black instead of crossfading; RGB_565 bands in dark skies at full size.
   **Sharp icons:** never scale a view that holds a vector icon; a scaled view stretches the
   rendered bitmap and blurs it. Size changes on focus go through padding or bounds in whole
   pixels. Do not resize one vector drawable back and forth either: resizing 48 -> 52 -> 48 px left
   it soft even with a fresh copy, so the round buttons stack a 48 px and a 52 px icon that are
   never resized and crossfade them. After a focus-and-leave the icon must be exactly as crisp as
   on a fresh start, and tests must check both states.
7. **Icons:** simple line icons, one stroke weight, drawn as vectors, with content descriptions.
   Use Material Symbols, outlined, drawn as vector drawables. Hand-draw only when Material has no
   matching icon. The Wi-Fi and Settings buttons use weight 400 in every state; all other icons
   stay at weight 300. Reason: thin white weight-300 strokes on translucent glass looked soft, and
   weight 400 plus a dark glass tint (#0B0E14 at 25%) fixed it.
8. **Restraint:** fewer elements, more space. If it does not help in the first five seconds, leave
   it out.
9. **Testing visuals:** check sharpness and contrast on the `tvtest_1080p` emulator (a true
   1920x1080 panel at density 320, like the Mi Box), not the resized Pixel 3a: its `wm size`
   override rescales every frame and blurs screenshots. Test on the real wallpapers, not only a
   plain background.
10. **Screensaver:** only the photo, the time and the date. No network; no weather, calendar,
    verse or anything personal; no per-frame animation (no Ken Burns) and no blur. It runs only
    while Android shows it on an idle TV and never starts anything on its own.
