# Wire extensions (Android → Mac, optional)

Everything here is **opt-in**: the tablet only sends these messages when the Mac
lists the matching name in `welcome.caps` (array of strings). Older Mac builds
omit `caps`, so they never receive a message they would misread. The tablet
announces what it can send in `hello.ext`.

Framing and base messages are unchanged (see WIRE.md in the Mac repo).

## hello additions

| key       | type     | meaning                                              |
|-----------|----------|------------------------------------------------------|
| `refresh` | number   | panel refresh rate in Hz — pick a matching capture rate |
| `ext`     | [string] | capabilities the tablet can send: `hover key click clip mode clipimg` |

## Modifier bitmask (`mods`)

`shift=1, ctrl=2, opt=4, cmd=8`. Also added (when non-zero) to `touch` messages,
so sticky sidebar modifiers work with taps and pen strokes.

## caps

| cap     | tablet → Mac | notes |
|---------|--------------|-------|
| `hover` | `{"type":"touch","phase":"hover","x":0..1,"y":0..1,...}` | pen (with the usual pen fields, pressure 0) or mouse hover; move the cursor, do not click |
| `key`   | `{"type":"key","code":<kVK>,"down":bool,"mods":int,"chars":"a","repeat":bool}` | `code` is a macOS virtual key (ANSI). `chars` is only set for plain typing (no Cmd/Ctrl). Used for the hardware keyboard, sidebar buttons and gestures |
| `click` | `{"type":"click","button":"right","x":..,"y":..,"mods":int}` | two-finger tap and mouse secondary button |
| `clip`  | `{"type":"clip","text":"…"}` both directions | plain text, ≤ 256 K chars. The tablet only sends while its app is in the foreground (Android restriction) |
| `mode`  | `{"type":"mode","mode":"mirror"\|"extend"}` | the sidebar toggle; the Mac should apply it to the virtual display. The Mac also states the session's actual mode in `welcome.mode`, which the tablet shows on its toggle |
| `viewport` | `{"type":"viewport","x":..,"y":..,"w":..,"h":..,"z":..}` | the zoomed-in part of the desktop, normalized; the Mac crops capture to it. Sent whenever the zoom changes, and again after each `welcome` while still zoomed |
| `clipimg` | `{"type":"clipimg","png":"<base64>"}` both directions | clipboard image, PNG ≤ 600 000 bytes (larger images are scaled down first). Needs `clip` on the Mac |

Pen `touch` messages also carry `barrel2: true` while the secondary stylus
button is held (squeeze / double-tap mappings on supported pens).

## Gesture → shortcut mapping (sent as `key` down+up)

| gesture | shortcut |
|---------|----------|
| 3-finger swipe left / right | Cmd+Z / Cmd+Shift+Z (undo / redo) |
| 3-finger pinch in / out | Cmd+C / Cmd+V |
| 4-finger swipe up / down | Ctrl+↑ / Ctrl+↓ (Mission Control / App Exposé) |
| 4-finger swipe left / right | Ctrl+→ / Ctrl+← (next / previous Space) |
