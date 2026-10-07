# macOS app: status of the Android features

The Mac app lives in `opendisplay/` (Swift). The wire formats are in
[WIRE-EXTENSIONS.md](WIRE-EXTENSIONS.md) and `opendisplay/PROTOCOL.md`.

> **None of the Mac code has been compiled or run on a Mac from this repo's
> sandbox.** The pure-logic pieces (`ViewportCrop`, `SystemShortcuts`,
> `AdbDevices`, `AdbBridge`, frame-rate selection) are typechecked and unit
> tested on Linux; everything that touches AppKit, ScreenCaptureKit, Network or
> SwiftUI was reviewed by reading only. Build, run `MacTests`, and walk the
> checklist at the bottom before releasing.

## Done

| Feature | Where |
|---|---|
| Caps in `welcome` (`hover key click mode viewport`, plus `clip clipimg` when sync is on) | `MacSender.sendWelcome` |
| Pen as a tablet (pressure, tilt, eraser, barrel buttons) | `InputInjector` |
| `hover`, `key`, `click`, stuck-key release | `InputInjector`, `MacSender.handleControl` |
| Pen second-button action is settable in the control panel | `ContentView` → `penBarrel2Action` |
| **Pinch-zoom crop** (`viewport`): capture `sourceRect` follows the tablet's zoom | `ViewportCrop`, `MacSender.applyViewportIfNeeded` |
| **Match device refresh rate** (opt-in, up to 120 fps, default off) | `VideoStreamConfiguration.requestedFrameRate`, `VirtualDisplay(refreshRate:)` |
| `welcome.mode` tells the tablet the session's real mirror/extend mode | `MacSender.sendWelcome` |
| Input works in **mirror** mode (it had no injector before) | `MacSender.start` |
| Mission Control opens directly when Ctrl+Up is switched off in System Settings; the control panel says which other gesture shortcuts are off | `SystemShortcuts`, `InputInjector` |
| Clipboard sync toggle, and **images** both ways (`clipimg`) | `ClipboardSync`, `ContentView` |
| **Android over USB** through `adb forward`, plug-in-and-go once "Detect Android devices over USB" is on | `AdbBridge`, `AdbDevices`, `SenderController` |
| **Reverse connect** listener on `:9011` + Bonjour `_opendisplay-mac._tcp`, opt-in, each new device confirmed | `ReverseListener`, `SenderTransport.incoming`, `SenderController.acceptReverse` |

### Both Android transports are opt-in on purpose

USB detection runs `adb`, which starts adb's background server; a server from a
different adb version than another tool's (Android Studio) can restart theirs.
So it is a toggle, off by default.

### Reverse connect is opt-in on purpose

The protocol has no authentication. A listener on the Mac lets anything on the
network ask to see and drive it, so it is off by default ("Let Android devices
connect to this Mac"), and the first connection from each address asks for
confirmation. Loopback (an `adb reverse` tunnel) is trusted. Do not make it
default-on without adding pairing.

## Not done

- **Mirror/extend switch without rebuilding the session.** `SenderController.restartAll()`
  still tears every session down; the Android app now rides that out (the last
  frame stays up with a "Reconnecting…" pill) but the Mac does not reconfigure in place.
- **"Connect to <recent device>" in the menu bar.** The device list already
  offers Connect for anything discovered; a recents list needs the receiver to
  be reachable, so it adds little.
- **Spaces / App Exposé without their shortcuts.** Only Mission Control has a
  public way to be opened directly; for the others the control panel points
  the user at the shortcut settings.
- **Touch Bar strip.** Needs a streamed Touch Bar surface and hardware that
  newer Macs no longer have.
- **Tablet microphone to Mac.** Needs a virtual audio input device (an Audio
  Server plug-in / driver); there is no public API to create one from an app.

## Checklist before shipping

1. `xcodebuild test -scheme OpenSidecarMac` (new: `ViewportCropTests`,
   `SystemShortcutsTests`, `AdbDevicesTests`, `StreamConfigurationTests`).
2. Pinch-zoom on the tablet: the picture should sharpen, not just magnify. If
   the crop lands in the wrong place, `SCStreamConfiguration.sourceRect`'s origin
   convention (display points, top-left) is the first thing to check.
3. Mirror mode: touch, keyboard and the pen act on the Mac.
4. `adb devices` shows the tablet; the row appears under Devices and connects.
5. With "Let Android devices connect" on, turn off the Mac → tablet path (VPN
   lockdown or AP isolation) and confirm the prompt appears once and the tablet
   streams. Decline once: the tablet must not be able to connect.
6. Copy an image on the Mac, paste on the tablet, and the reverse.
7. "Match device refresh rate" on a 90/120 Hz tablet: check `stream selected`
   in the log shows the rate, and that the picture does not stutter. If macOS
   refuses the faster virtual-display mode the log shows `applySettings FAILED`.
