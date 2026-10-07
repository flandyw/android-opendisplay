# OpenDisplay for Android

Use an **Android** phone or tablet as a **second monitor** for a Mac running this
OpenDisplay Mac app (or upstream
[peetzweg/opendisplay](https://github.com/peetzweg/opendisplay)).

This repo is the **Android receiver** (APK). It targets plain Android from
**API 26 (Android 8.0)** up, and is ROM-agnostic: AOSP, stock OEM builds, and
hardened ROMs such as **[GrapheneOS](https://grapheneos.org/)** all run the same
APK. GrapheneOS just happens to be the ROM the maintainer tests on (see
[Verified](#verified)); its stricter VPN defaults are called out where they
matter.

Same wire protocol as the iOS/macOS sender. **Network is the default**; USB is
optional.

## Features

**Display**

- Extend (virtual Mac display) or Mirror
- Hardware H.264 decode (`MediaCodec`)
- Portrait / landscape

**Interface**

- Material 3 Expressive controls, emphasized typography, and spring motion
- System light/dark themes and wallpaper colors on Android 12+
- Adaptive phone/tablet connection screen with a two-step Wi-Fi or USB guide
- **Connect with an address** opens copyable network addresses; **Help** opens
  expandable network, VPN, USB, touch, and device information
- The Mac desktop takes over automatically when streaming starts

**Connect**

- **Wi‑Fi / LAN** — listen on **:9000**; Mac dials when peer TCP works
- **mDNS** — advertise `_opensidecar._tcp` with TXT `sig=OpenDisplay`
- **Reverse connect** — when Mac→device TCP is blocked (AP isolation, guest
  Wi‑Fi, many VPNs), the Mac listens on **:9011** (`_opendisplay-mac._tcp`)
  and this app **dials the Mac**. Same stream protocol after TCP is up.
  With USB debugging attached, Mac can set `adb reverse tcp:9011` so the
  device dials `127.0.0.1:9011` if pure Wi‑Fi outbound is blocked.
- **VPN help in-app** — **Help → Wi-Fi won’t connect with a VPN** explains
  Always-on vs lockdown and opens system VPN settings (apps cannot
  disable the kill switch).
- **USB + adb** — `adb forward tcp:9000` (Mac dials loopback)
- **USB tethering** — optional path without debugging

**Why reverse connect?**  
Classic Sidecar-style apps assume the computer can open a TCP connection to
the tablet. Home/guest routers often allow discovery (mDNS/ping) but **drop
peer TCP**. Reverse connect flips dial direction so streaming can still work
on those networks.

**Input & audio**

- Touch click / drag / two-finger scroll; pinch-zoom (the Mac crops its capture to the zoomed area, so zoom shows real detail)
- Stylus: pen always acts as a pointer (pressure, tilt, barrel button sent as optional `touch` fields; palm rejection while the pen is down). Pressure/tilt need Mac-app support; older Macs treat it as a plain click/drag.
- Sidecar-style gestures: 3-finger swipe = undo/redo, pinch = copy/paste; 4-finger swipe = Mission Control / Spaces; two-finger tap = right-click
- Sidebar (tab on either screen edge — tap or drag it open; it tucks itself away after a few idle seconds and scrolls on short screens): sticky ⌘ ⌥ ⌃ ⇧ (tap = next click only, long-press = lock; a dot on the tab shows one is armed), Esc, undo/redo, copy/paste, app switcher, Mission Control, Spotlight, Dock toggle, on-screen keyboard, pen-only mode (fingers scroll), mirror/extend, one-tap zoom reset, stats HUD, dim screen (panel to minimum brightness, stream keeps running), and a switch to dock the sidebar left or right
- On-screen keyboard types into the Mac (autocorrect off, so the Mac sees what you typed); sticky modifiers combine with it (⌘ then `c` = copy). Needs Mac-app `key` support
- Hardware keyboard keys include the numeric keypad
- Hardware keyboard, mouse/trackpad (hover, right-click, wheel) and clipboard sync (text, and images up to ~600 KB; turn on "Sync clipboard" in the Mac app)
- Auto-reconnect when a reverse (tablet → Mac) session drops; the last frame stays up with a “Reconnecting…” pill for a few seconds, so rotation, mirror/extend switches and Wi‑Fi blips don't flash the connection screen. Matches the display's highest refresh rate
- Features marked above beyond basic touch need Mac-app support — see [WIRE-EXTENSIONS.md](WIRE-EXTENSIONS.md); without it they stay inactive
- Mac cursor overlay
- System audio to device speakers when enabled on the Mac

**Reliability**

- Foreground service; stream survives Home / recents
- Low-latency Wi‑Fi lock held while connected (no power-save jitter)

## What works

Verified behavior on the reference device (other Android builds should match;
see [Verified](#verified)):

| Feature | Status |
|---|---|
| TCP listen on port **9000** + `hello` | Works |
| H.264 hardware decode (MediaCodec) | Works |
| Touch click / drag + two-finger scroll | Works |
| Pinch-to-zoom + pan when zoomed + double-tap reset | Works |
| Mac cursor overlay | Works |
| mDNS (`_opensidecar._tcp` + signature) | Works when LAN allows multicast |
| **Reverse connect** (dial Mac `:9011`) | Works when device→Mac TCP (or adb reverse) works |
| Manual IP connect | Works when Mac→device TCP works |
| USB + adb | Works |
| USB tethering | Works (may affect Mac internet) |
| System audio | Mac → device when streaming |
| Background keep-alive | Foreground service |

## Platform

| | |
|---|---|
| **Target OS** | **Android 8.0+ (API 26)** — AOSP, OEM builds, custom ROMs (GrapheneOS included) |
| **Minimum API** | **26** (build baseline) |
| **Compile API** | **36** |
| **Target API** | **35** |
| **Required** | Hardware **H.264 / AVC** decoder |

### Verified

| Device | ROM / OS | Result |
|---|---|---|
| **Google Pixel Tablet** | **GrapheneOS** | Stream + touch + cursor (reverse network / USB) |

Anything beyond that row is **untested**. Reports and PRs for other Android
devices, OEM builds and ROMs are welcome — run the
[smoke checklist](#smoke-checklist) and share the result.

## How we keep builds working

1. **compileSdk 36 / targetSdk 35 / minSdk 26** — the floor is Android 8.0; we still version-guard platform APIs.
2. **Decode** — `KEY_LOW_LATENCY` on API 30+; vendor keys with plain MediaCodec fallback.
3. **Network** — cleartext TCP on LAN (`network_security_config`); classic listen `:9000` plus reverse dial to Mac `:9011`.
4. **Discovery is optional** — **Connect with an address** shows **IP:port** when mDNS is blocked.
5. **Startup probe** — logs API level, ABI, AVC decoder (`adb logcat -s DeviceReport H264Decoder`).
6. **CI** — unit tests + debug APK assemble (see `.github/workflows/android.yml`).
7. **Manual smoke** — [checklist](#smoke-checklist) on a real device before release.

## Build & install

```sh
./gradlew :app:assembleDebug
# Version = git commit count → ~/OpenDisplay-X.Y.Z-debug.apk
adb install -r ~/OpenDisplay-*-debug.apk
```

Needs **JDK 25+** and the Android SDK (platform 36). The Gradle wrapper pins the
toolchain, so no local Gradle install is required.

## Connect from the Mac

### Network (default)

1. Open this app on the Android device (leave it on the waiting screen).
2. Same Wi‑Fi as the Mac (avoid guest Wi‑Fi / VPNs that block LAN if you can).
3. Mac OpenDisplay → **Connect over network** (IP filled from discovery when possible).
4. Grant Mac **Screen Recording** + **Accessibility** if prompted.

**If classic dial fails** (`nc <device-ip> 9000` times out): reverse connect
kicks in — Mac listens on **9011**, this app dials the Mac. Keep the app open.
On the Mac turn on **Let Android devices connect to this Mac** (it is off by
default, and the Mac asks you to approve each new device).
With USB debugging connected, Mac may use `adb reverse tcp:9011` so the app
can dial `127.0.0.1:9011` when pure Wi‑Fi peer traffic is blocked.

### USB (adb — keeps Mac internet)

USB debugging does **not** install a Mac default route, so **Wi‑Fi keeps working**.

1. Enable **Developer options → USB debugging** (AOSP-style ROMs: **Settings → System → Developer options**).
2. Cable device ↔ Mac; accept the debugging prompt if shown.
3. Mac OpenDisplay → turn on **Detect Android devices over USB** (needs
   [platform-tools](https://developer.android.com/tools/releases/platform-tools)
   `adb` on PATH or the usual SDK location); the device shows up under Devices
   and connects when you plug it in.
4. **Do not enable USB tethering** unless adb is unavailable.

Manual equivalent:

```sh
adb forward tcp:9000 tcp:9000
# Mac → manual connect 127.0.0.1 port 9000
```

### USB without debugging (tethering)

Android **USB tethering** creates an RNDIS/NCM link so the Mac can reach the device without adb. **Side effect:** macOS often makes the phone the default route and **Wi‑Fi appears offline**. OpenDisplay then best-effort **removes only that tether default route** so Wi‑Fi is primary again while still using the cable for the display session.

**Setup checklist**

1. Cable device ↔ Mac (data cable, not charge-only).
2. USB notification / **Settings → USB**.
3. **USB controlled by → Connected device** (this computer).
4. Enable **USB tethering** (**Settings → Network & internet → Hotspot & tethering → USB tethering**). Toggle off→on if the Mac row stays “accessory/charging”.
5. OpenDisplay Android app → mode **USB**.
6. Mac OpenDisplay → **Android USB** — label should become **“Android USB (tether)”** (not “accessory/charging”). In **System Settings → Network** you should see a **Pixel Tablet** (or similar) interface with an IPv4 address (often `192.168.42.x`).

**If Mac shows the device on USB but “no devices” / connect fails:** the cable is up but the device is not in a network or adb USB mode (e.g. product id accessory `0x4EE1`). Re-enable **USB tethering**, or turn on **USB debugging**. Charge-only mode will never work.

**Mac internet:** USB tethering often ranks the device **above Wi‑Fi** in Network Service Order (internet “dies”). While OpenDisplay is open it automatically puts **Wi‑Fi above** Pixel/Android USB services. If internet is still broken: **System Settings → Network → ⋯ → Set Service Order** → drag **Wi‑Fi** to the top, or turn tethering off.

Grant Mac **Screen Recording** + **Accessibility** if prompted.

## Smoke checklist

Run on the Android device/ROM you plan to support (reference device: Pixel
Tablet on GrapheneOS):

- [ ] App launches; connection screen shows the advertised device name; **Connect with an address** shows a LAN IP and port **9000** when available.
- [ ] Switch Wi-Fi / USB; open Help and copy an address. Check portrait, landscape, light/dark mode, and enlarged text.
- [ ] `adb logcat -s DeviceReport` shows **H.264 decoder: …** (not MISSING).
- [ ] Mac connects (discovery, reverse, **or** manual IP / USB).
- [ ] Extended display appears; desktop is visible (not black for >3s).
- [ ] Mouse cursor visible on the device when the pointer is on that display.
- [ ] Tap = click, drag = drag, two-finger pan = scroll.
- [ ] Rotate device once; Mac rebuilds the display (may briefly reconnect).
- [ ] Leave app (home); session ends cleanly; reopen and reconnect.

### Emulators (optional)

```sh
# Examples — create AVDs in Android Studio Device Manager
emulator -avd Pixel_3a_API_26 &   # floor
emulator -avd Pixel_6_API_34 &    # modern
adb install -r app/build/outputs/apk/debug/OpenDisplay-*-debug.apk
```

Emulator networking to a Mac on the host: use the emulator’s IP as shown in the app, or `adb forward` from the host Mac.

## Troubleshooting

| Symptom | What to try |
|---|---|
| Mac doesn’t list the device | Same LAN; Local Network on Mac; manual **IP:9000**; VPN / AP isolation |
| Wi‑Fi fails; USB / `adb reverse` works | OS VPN **lockdown** — see [below](#vpn-lockdown) |
| `nc <ip> 9000` times out | Peer TCP blocked — fix lockdown, reverse connect, or USB/adb |
| Reverse connects then drops | Keep app open; allow LAN in VPN apps; avoid guest Wi‑Fi |
| Black screen | Decoder in logcat; Mac quality **Fast**; wait for keyframe |
| No cursor | Move pointer onto the virtual display |
| High latency | Prefer 5 GHz Wi‑Fi; Mac quality **Balanced** or **Fast** |
| Connect then drop | Keep app foreground; don’t lock screen mid-session |
| VPN / ExpressVPN / Nord | OS lockdown **off** and app **Allow LAN** / Network Lock off |

### VPN lockdown

Some Android builds — **[GrapheneOS](https://grapheneos.org/)** is the notable
one — turn **both** toggles on when you first set up any VPN:

| Toggle | What it does |
|---|---|
| **Always-on VPN** | Keeps that VPN selected; may restart the tunnel |
| **Block connections without VPN** | Kill switch — only VPN paths allowed |

**Block connections without VPN** (lockdown) is the one that breaks
Mac ↔ device Wi‑Fi:

- Blocks LAN peer TCP (classic `:9000` and reverse `:9011`)
- Still blocks when the VPN app looks **disconnected**
- VPN-app “Allow LAN” does **not** override OS lockdown
- `adb reverse` + dial `127.0.0.1` can still work (loopback, not LAN)

**Turn it off for pure Wi‑Fi:**

1. **Settings → Network & internet → VPN**
2. Tap the **gear** on each listed VPN
3. Turn **off** **Block connections without VPN**
4. Optionally turn **Always-on VPN** off too
5. Toggle Wi‑Fi, then retry **Connect over network**

In OpenDisplay, **Help → Wi-Fi won’t connect with a VPN** shows
these steps and opens **VPN settings**. A normal app **cannot** change
this toggle (Device Owner / user only).

Also in the VPN app: enable **Allow LAN** / **Local network sharing**
and disable any Network Lock / app kill switch if present.

| Goal | Always-on | Block without VPN |
|---|---|---|
| Max privacy on the go | ON | ON |
| Home LAN + OpenDisplay Wi‑Fi | optional | **OFF** |
| VPN only when you open the app | OFF | OFF |

## Releases & updates

Signed builds are published to <https://opendisplay.flandolf.me/releases/> (no GitHub
releases). The app checks that server on launch (once a day; **App updates** on the
connection screen has a manual check and a switch), verifies the SHA-256, and installs
through a `PackageInstaller` session. The debug build (`.debug` package) can't be
updated this way.

- **Versions** come from the git commit count: stable `X.Y.Z` with code `count*10000`
  (`count/100 . count/10%10 . count%10`); experimental builds are `X.Y.Z-exp.N` with code
  `count*10000 + N`, so they sort below the next commit's build.
- **`./build.sh`** reserves the next `N` (`tools/next-experimental-build.py`, state in ignored
  `.tooling/`), builds a signed release with `.signing/opendisplay-release.p12`, verifies the
  signature/package/version and asks to publish (`-p` publishes, `-n` builds only).
  Back up `.signing/`: the key is what lets Android update installs in place.
- **Server** is the [`release-server`](https://github.com/flandyw/release-server) submodule
  (`git submodule update --init`), configured by `release-server.conf` (service
  `opendisplay-releases`, port 8788, Cloudflare in front). Set up with
  `./release-server/install.sh none`, then `./release-server/pin-cert.sh <signed.apk>`.

## Permissions

- Internet / network state (TCP)
- Wi‑Fi multicast (mDNS)
- Install packages (self-update from the release server)
- Nearby Wi‑Fi devices on API 33+ (discovery-related; not used for location)

No camera, mic, or storage.

## Layout

```
app/src/main/java/app/opendisplay/receiver/
  MainActivity.kt
  OpenDisplayApp.kt
  ConnectionMode.kt
  ReceiverForegroundService.kt
  audio/AudioPlayer.kt            # system audio to device speakers
  compat/DeviceReport.kt          # version / codec probe
  input/TouchMapper.kt
  input/TextForwarder.kt          # on-screen keyboard edits → Mac keystrokes
  net/ReceiverServer.kt
  net/FrameCodec.kt
  net/NsdAdvertiser.kt
  net/MacHostBrowser.kt
  net/DiscoveryProbe.kt
  net/WifiNetworkHolder.kt
  update/                         # release-server self-update (check, verify, install)
  ui/CursorOverlayView.kt
  video/H264Decoder.kt            # version-safe low-latency
  video/AnnexBParser.kt
  protocol/WireProtocol.kt
app/src/test/java/app/opendisplay/receiver/FrameCodecTest.kt
```
