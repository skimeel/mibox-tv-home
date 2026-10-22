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
   remove or shorten it.
6. **Cost:** no blur or RenderScript, no big shadows on many views, no full-screen overdraw. Keep
   total launcher memory under 40 MB.
7. **Icons:** simple line icons, one stroke weight, drawn as vectors, with content descriptions.
   Use Material Symbols, outlined, with the weight chosen in the icon audit, drawn as vector
   drawables. Hand-draw only when Material has no matching icon.
8. **Restraint:** fewer elements, more space. If it does not help in the first five seconds, leave
   it out.
