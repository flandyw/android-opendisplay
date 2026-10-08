# Expanded Android sidebar verification

Source visual truth: `/home/ubuntu/.t3/userdata/attachments/15673f52-562e-49d4-9b9b-07a96b8a819d-d5fb3706-e29f-4f6c-a0c8-4d4a4f9ead6f.png` (2973 × 2288 px, including device bezel).

Implementation: `app/src/main/java/app/opendisplay/receiver/ui/SidebarOverlay.kt`, native Jetpack Compose.

Target state: expanded sidebar, landscape tablet, left edge, keyboard and modifiers inactive. The reference contains a full-height black rail with two shortcuts near the top, four modifiers in the middle, and undo/keyboard/hide near the bottom. The implementation uses a 64dp rail, 48dp touch targets, and flexible gaps between these groups. Short viewports can scroll; right-edge docking is supported.

Implementation screenshot and full-view/focused comparison evidence: unavailable. T3 `device_list` reports “Agent device access is turned off for this environment.” Local `adb devices -l` reports no attached devices; no emulator is installed. Source was opened, but a rendered implementation cannot be captured. No density normalization or visual comparison was performed. This is native Android work; CSS viewport and browser console checks do not apply.

Required fidelity surfaces, pending rendered verification:
- Typography: expanded rail uses vector icons, with localized accessibility labels and existing menu typography.
- Spacing/layout: black rail occupies the reserved stream strip; three groups use flexible gaps. Rendered spacing and scroll behavior still need device verification.
- Colors: black background, white outlines, blue armed state, teal locked state. Rendered contrast still needs verification.
- Assets: Lucide icons converted to Android vector resources; license retained in `third_party/lucide-LICENSE`. The menu bar, Dock, and hide icons are library equivalents rather than Apple's exact symbols.
- Content: the reference's nine controls are present. Escape, pen-only mode, zoom reset, and existing extra controls are accessible by holding Hide sidebar; the stream hint explains this action. The existing Quit action is retained below the rail controls.

Findings: visual verification is blocked by unavailable Android device access. No pixel-fidelity conclusion is claimed.

Comparison history: no rendered comparison available.

Implementation checklist:
- Debug APK compilation and all 67 unit tests pass. `git diff --check` passes.
- Android lint reports four existing errors outside the sidebar: missing TV launcher intent filter and three restricted API findings on `super.dispatchKeyEvent` in `MainActivity`. No sidebar findings were reported.
- Pending on-device checks: expanded layout, modifier tap/hold, keyboard toggle, menu bar/Dock shortcuts, long-press extras, collapse/reopen, right docking, short-screen scrolling, and touch isolation from the stream.

final result: blocked

# Main connection screen redesign — 2026-10-08

Scope: the native Android connection screen in `app/src/main/java/app/opendisplay/receiver/ui/ConnectionScreen.kt`. The existing Compose implementation and `OpenDisplayTheme` provide the design context; no rendered main-screen reference was supplied.

The tablet layout separates the introduction and device identity from the primary connection panel. Discovered Macs are clickable rows, manual Mac entry remains available, Wi-Fi instructions expand on demand, and USB instructions appear when USB is selected. Device address and auto-connect live together. The header exposes Help and App updates; an update-required connection problem offers App updates directly when available. Searching, connecting, connected, offline, and error states use the existing typed connection state.

The screen retains Material 3 Expressive typography and motion, dynamic colors, and system light/dark themes. At widths below 840dp or font scales of 1.35 and higher, sections stack and scroll. Full advertised device names wrap, Mac rows retain full names in their text semantics, and the auto-connect row remains a single switch target.

Validation: 98 existing unit tests pass, the debug APK assembles, and `git diff --check` passes. Lint reports the same four errors outside this screen: MissingLeanbackLauncher in the manifest and three RestrictedApi findings in MainActivity. No ConnectionScreen lint findings were reported.

Rendered verification remains unavailable: T3 device access is disabled. Compose preview configurations cover phone and tablet discovery, searching, USB, large text, offline, desktop handoff, and update-required states, but they have not been rendered here. No screenshot, pixel-fidelity, or on-device usability conclusion is claimed.

Pending device checks: landscape/portrait layout and scrolling, 1.5×/2× text, long device/Mac names, discovery and manual connection, both mode selectors, expanding setup instructions, address copying, auto-connect toggle, Help and update sheets, and automatic transition into the streamed desktop.
