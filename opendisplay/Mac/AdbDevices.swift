import Foundation

/// An Android device `adb devices -l` can see.
struct AdbDevice: Equatable, Hashable {
    let serial: String
    /// "device" is ready; "unauthorized" is waiting for the USB-debugging
    /// prompt on the device; "offline" is mid-reconnect.
    let state: String
    let model: String?
    /// Seen over the cable rather than `adb connect` / wireless debugging.
    let overUSB: Bool

    var isReady: Bool { state == "device" }

    var displayName: String {
        model.map { $0.replacingOccurrences(of: "_", with: " ") } ?? serial
    }
}

enum AdbDevices {
    /// Parses `adb devices -l`. Ignores the header, daemon chatter and blank
    /// lines; emulators are skipped because they have no cable to speak of.
    static func parse(_ output: String) -> [AdbDevice] {
        output.split(whereSeparator: \.isNewline).compactMap { line in
            let fields = line.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
            guard fields.count >= 2, fields[0] != "List", !fields[0].hasPrefix("*"),
                  !fields[0].hasPrefix("emulator-") else { return nil }
            let properties = Dictionary(
                fields.dropFirst(2).compactMap { field -> (String, String)? in
                    guard let colon = field.firstIndex(of: ":") else { return nil }
                    return (String(field[..<colon]), String(field[field.index(after: colon)...]))
                },
                uniquingKeysWith: { first, _ in first })
            return AdbDevice(serial: fields[0], state: fields[1],
                             model: properties["model"], overUSB: properties["usb"] != nil)
        }
    }

    /// `adb forward tcp:0 tcp:N` prints the local port it picked.
    static func parseForwardedPort(_ output: String) -> UInt16? {
        UInt16(output.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    /// Where `adb` may live, most likely first. The caller checks existence.
    static func candidatePaths(environmentPath: String?, home: String) -> [String] {
        let fromPath = (environmentPath ?? "").split(separator: ":").map { "\($0)/adb" }
        return fromPath + [
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb",
            "\(home)/Library/Android/sdk/platform-tools/adb",
            "\(home)/Android/Sdk/platform-tools/adb",
        ]
    }
}
