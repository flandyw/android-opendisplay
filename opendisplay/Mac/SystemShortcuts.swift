import Foundation

/// macOS "symbolic hot keys" the tablet's gestures and sidebar lean on. The
/// tablet sends them as Ctrl+arrow key presses, which only do something while
/// the matching shortcut is switched on in System Settings → Keyboard →
/// Keyboard Shortcuts → Mission Control.
enum SymbolicHotKey: Int, CaseIterable {
    case missionControl = 32       // Ctrl+Up
    case applicationWindows = 33   // Ctrl+Down
    case moveLeftASpace = 79       // Ctrl+Left
    case moveRightASpace = 81      // Ctrl+Right

    /// What the shortcut does, as the settings pane words it.
    var title: String {
        switch self {
        case .missionControl: return "Mission Control"
        case .applicationWindows: return "Application windows"
        case .moveLeftASpace: return "Move left a space"
        case .moveRightASpace: return "Move right a space"
        }
    }
}

enum SystemShortcuts {
    /// `AppleSymbolicHotKeys` from the `com.apple.symbolichotkeys` domain, or
    /// nil when it can't be read. A shortcut missing from the table is on:
    /// macOS only writes an entry once the user changes it.
    static func isEnabled(_ key: SymbolicHotKey, in table: [String: Any]?) -> Bool {
        guard let entry = table?[String(key.rawValue)] as? [String: Any] else { return true }
        let raw = entry["enabled"]
        if let flag = raw as? Bool { return flag }
        if let number = raw as? Int { return number != 0 }
        return true
    }

    static func disabled(in table: [String: Any]?) -> [SymbolicHotKey] {
        SymbolicHotKey.allCases.filter { !isEnabled($0, in: table) }
    }

    /// The shortcut a plain Ctrl+arrow key press stands for (`mods` is the
    /// wire bitmask: shift=1, ctrl=2, opt=4, cmd=8).
    static func hotKey(forKeyCode code: Int, mods: Int) -> SymbolicHotKey? {
        guard mods == 2 else { return nil }
        switch code {
        case 126: return .missionControl
        case 125: return .applicationWindows
        case 123: return .moveLeftASpace
        case 124: return .moveRightASpace
        default: return nil
        }
    }

    /// Mission Control can be opened directly, so the tablet's button keeps
    /// working when its Ctrl+Up shortcut is off. The other three have no
    /// public equivalent.
    static func shouldOpenMissionControl(keyCode: Int, mods: Int, table: [String: Any]?) -> Bool {
        hotKey(forKeyCode: keyCode, mods: mods) == .missionControl
            && !isEnabled(.missionControl, in: table)
    }
}
