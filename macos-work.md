# macOS app work for the new Android features

The Android app now sends several optional messages, but only when the Mac lists
them in `welcome.caps`. Until the Mac does that, those features stay inactive on
the tablet. Message formats are in [WIRE-EXTENSIONS.md](WIRE-EXTENSIONS.md); this
file is the checklist for the Mac side.

Everything below is Swift in the Mac repo (`Shared/Protocol.swift`, `WIRE.md`,
the receiver/session code). None of it lives in this repo.

## 0. Do this first: advertise capabilities

1. In the `welcome` message, add `"caps": [...]` listing only what is implemented.
   Add names one at a time as each feature lands:
   `"hover"`, `"key"`, `"click"`, `"clip"`, `"mode"`.
2. Parse the new `hello` fields (all optional, ignore if absent):
   - `refresh` (number, Hz)
   - `ext` (array of strings)
3. Keep ignoring unknown message types and unknown JSON keys. The tablet relies
   on this for `mods`, `barrel2`, `tool`, `pressure`, `tilt`, `azimuth`.
4. Mirror the new message names and cap strings into `Shared/Protocol.swift`
   and `WIRE.md`. Do not bump the protocol version for these; they are gated by
   caps, not by `pv`.

## 1. Pen as a real tablet (highest value)

The tablet already sends these on `touch` messages: `tool` (`stylus`/`eraser`),
`pressure` (0..1), `tilt` and `azimuth` (radians), `barrel`, and the new `barrel2`.

- Post events with `CGEvent` using the tablet fields so Photoshop, Krita,
  Preview and others see a real pen:
  - `mouseEventSubtype = NSEventSubtype.tabletPoint` (value 1)
  - `kCGTabletEventPointPressure`, `kCGTabletEventTiltX` / `TiltY`
  - Convert tilt + azimuth to tilt X/Y in -1..1 (`tiltX = sin(tilt) * cos(azimuth)`,
    `tiltY = sin(tilt) * sin(azimuth)`, check sign against a real app).
  - For `tool == "eraser"`, also post a tablet proximity event with the eraser
    pointer type, and flip back to pen on the next stylus event.
- Map `barrel` to right-click (or a user-configurable action). Map `barrel2` to a
  second configurable action (tablet sends it for squeeze / double-tap).
- Add a settings UI for those two mappings.

## 2. `hover` cap

- Handle `touch` with `phase == "hover"`: move the cursor to (x, y) only. Never
  click, never start a drag. For a pen it carries the same pen fields with
  pressure 0; post a tablet proximity-in event so apps can show a brush preview.
- The tablet sends hover continuously while the pen or mouse is above the
  screen, so keep this path cheap (no logging, no allocations per event).
- Advertise `"hover"` in `caps` once done.

## 3. `key` cap (keyboard, sidebar, gestures)

Message: `{"type":"key","code":<kVK>,"down":bool,"mods":int,"chars":"…","repeat":bool}`

- `code` is a macOS virtual key code (ANSI layout). Create the event with
  `CGEvent(keyboardEventSource:virtualKey:keyDown:)`.
- `mods` is a bitmask: shift=1, ctrl=2, opt=4, cmd=8. Convert to
  `CGEventFlags` (`.maskShift`, `.maskControl`, `.maskAlternate`, `.maskCommand`)
  and set them on the event.
- `chars` is present only for plain typing (no Cmd/Ctrl). If present, call
  `keyboardSetUnicodeString` so the tablet's layout is honored; if absent, rely
  on the key code + flags (shortcuts).
- `repeat` is true for auto-repeat; set `keyboardEventAutorepeat`.
- Sends arrive as separate down and up messages. Gestures and sidebar buttons
  send a down+up pair back to back.
- The sidebar's modifier buttons also add `mods` to `touch` and `click`
  messages. Apply `mods` as event flags on the synthesized mouse events too, so
  Cmd-click and Shift-click work.
- Needs the Accessibility permission (already needed for touch input).
- Safety: release stuck keys. If the connection drops, post key-up for every
  key and modifier that is still down.

Shortcuts the tablet will send through this path (so no extra Mac code beyond
`key`), but these depend on the user's System Settings keyboard shortcuts:

| Gesture | Keys |
|---|---|
| 3-finger swipe left/right | Cmd+Z / Cmd+Shift+Z |
| 3-finger pinch in/out | Cmd+C / Cmd+V |
| 4-finger swipe up/down | Ctrl+Up / Ctrl+Down (Mission Control / App Exposé) |
| 4-finger swipe left/right | Ctrl+Right / Ctrl+Left (switch Space) |

Note: the Ctrl+arrow shortcuts are disabled by default in System Settings >
Keyboard > Keyboard Shortcuts > Mission Control. Either mention this in the Mac
app's UI, or trigger Mission Control and Spaces directly through the private
CoreDock / `CGSSpace` APIs instead.

## 4. `click` cap

Message: `{"type":"click","button":"right","x":0..1,"y":0..1,"mods":int}`

- Convert (x, y) with the same mapping used for `touch`, then post a
  `rightMouseDown` + `rightMouseUp` at that point, with `mods` as flags.
- Only `"right"` is sent today; accept `"left"` and `"middle"` for later.

## 5. `clip` cap (both directions)

Message: `{"type":"clip","text":"…"}`, plain text, up to 256 K characters.

- Tablet to Mac: set `NSPasteboard.general` to the text.
- Mac to tablet: watch `NSPasteboard.general.changeCount` (poll about every
  500 ms; there is no notification API) and send `clip` when it changes and holds
  plain text.
- Avoid echo loops: remember the last text you set or sent and skip it when it
  comes back.
- Skip sending for pasteboard items marked concealed or transient
  (`org.nspasteboard.ConcealedType`, `TransientType`), so password-manager
  copies never leave the Mac.
- Add an on/off setting; clipboard sync is a privacy decision the user should make.

## 6. `mode` cap (mirror vs extend)

Message: `{"type":"mode","mode":"mirror"|"extend"}`

- `extend`: current behavior (virtual display as a separate screen).
- `mirror`: mirror the main display to the virtual display
  (`CGConfigureDisplayMirrorOfDisplay`), restoring the previous arrangement on
  `extend` and on disconnect.
- The tablet flips its toggle only if the Mac advertises `mode`; it does not wait
  for an ack today. Optionally reply with `{"type":"mode","mode":...}` if the
  change failed, so a later tablet build can revert its toggle.

## 7. `refresh` in hello

- Use `hello.refresh` (e.g. 120) as the virtual display's refresh rate and the
  ScreenCaptureKit `minimumFrameInterval`, capped by what the encoder and link can
  sustain. Today the tablet's refresh rate is ignored.

## 8. Connection polish (Mac menu bar)

- Show a "Connect to <tablet name>" item in the menu bar for recently seen
  tablets, so reconnecting is one click.
- Keep the reverse-dial Bonjour advertisement (`_opendisplay-mac._tcp.`) up for
  as long as the Mac app runs. The tablet now re-dials by itself with backoff
  after a drop, so a restarted Mac app is picked up automatically if the
  listener on `MAC_REVERSE_PORT` (9011) comes back.

## 9. Not started on either side

- Touch Bar emulation: needs the Mac to stream Touch Bar contents.
- Tablet microphone to Mac, and image clipboard.
- A wired USB path as a first-class mode in the Mac UI (adb reverse works today).

## Suggested order

1. Section 0 (caps plumbing), then 1 (pen), the biggest visible win.
2. `key` (3), then `click` (4), since gestures, sidebar and the keyboard all depend on them.
3. `hover` (2), `clip` (5), `mode` (6), `refresh` (7), menu bar (8).

## How to test each piece

- Run the tablet app and the Mac app, check the tablet's log for
  `welcome from Mac pv=… caps=[…]`, which confirms your caps are being read.
- With `key`: pair a Bluetooth keyboard with the tablet and type into a Mac text
  field; also tap the sidebar's undo/redo.
- With `click`: two-finger tap on the tablet.
- With `hover`: hover a stylus or connect a mouse to the tablet and watch the
  Mac cursor move without clicking.
- With `clip`: copy text on each side and paste on the other.
