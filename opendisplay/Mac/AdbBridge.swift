import Foundation

/// The port the Android receiver listens on (`WireProtocol.DEFAULT_PORT`).
private let adbReceiverPort = 9000

/// Finds Android devices over USB through `adb` and forwards a local port to
/// the receiver's listener on each, so the Mac can dial a cabled device
/// without Wi-Fi: `adb forward tcp:<free port> tcp:9000`, then connect to
/// 127.0.0.1:<port>. Needs Android platform-tools; without adb on the Mac the
/// bridge stays idle and the rest of the app is unaffected.
@MainActor
final class AdbBridge {
    /// Devices adb reports (ready ones are forwarded).
    private(set) var devices: [AdbDevice] = []
    /// serial → local port reaching the receiver on that device.
    private(set) var ports: [String: UInt16] = [:]
    /// False when adb could not be found, so the UI can say why nothing shows.
    private(set) var available = false

    /// Fired when the set of ready, forwarded devices changes.
    var onChange: (() -> Void)?

    private var adbPath: String?
    private var timer: Timer?
    private var polling = false

    func start() {
        guard timer == nil else { return }
        adbPath = Self.locateAdb()
        available = adbPath != nil
        guard adbPath != nil else {
            Log.info("adb not found — Android USB needs Android platform-tools (brew install android-platform-tools)")
            return
        }
        Log.info("adb at \(adbPath ?? "?")")
        poll()
        timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.poll() }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        guard !devices.isEmpty || !ports.isEmpty else { return }
        devices = []
        ports = [:]
        onChange?()
    }

    private func poll() {
        guard let adb = adbPath, !polling else { return }
        polling = true
        let known = ports
        Task.detached(priority: .utility) { [weak self] in
            let listed = runProcess(adb, ["devices", "-l"]).map(AdbDevices.parse) ?? []
            var forwards: [String: UInt16] = [:]
            for device in listed where device.isReady {
                if let port = known[device.serial] {
                    forwards[device.serial] = port
                } else if let output = runProcess(adb, ["-s", device.serial, "forward", "tcp:0",
                                                        "tcp:\(adbReceiverPort)"]),
                          let port = AdbDevices.parseForwardedPort(output) {
                    Log.info("adb forward \(device.serial) → 127.0.0.1:\(port)")
                    forwards[device.serial] = port
                }
            }
            let result = forwards
            await MainActor.run { [weak self] in
                guard let self else { return }
                self.polling = false
                // Stopped while the poll was running: drop its answer.
                guard self.timer != nil else { return }
                let changed = self.ports != result
                    || self.devices.map(\.serial) != listed.map(\.serial)
                    || self.devices.map(\.state) != listed.map(\.state)
                self.devices = listed
                self.ports = result
                if changed { self.onChange?() }
            }
        }
    }

    // MARK: - Process helpers

    private static func locateAdb() -> String? {
        AdbDevices.candidatePaths(
            environmentPath: ProcessInfo.processInfo.environment["PATH"],
            home: NSHomeDirectory())
            .first { FileManager.default.isExecutableFile(atPath: $0) }
    }
}

/// Runs a command and returns its output, or nil if it failed to launch, did
/// not finish within a few seconds, or exited non-zero.
private func runProcess(_ path: String, _ arguments: [String]) -> String? {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: path)
    process.arguments = arguments
    let pipe = Pipe()
    process.standardOutput = pipe
    process.standardError = Pipe()
    do { try process.run() } catch { return nil }
    let killer = DispatchWorkItem { if process.isRunning { process.terminate() } }
    DispatchQueue.global().asyncAfter(deadline: .now() + 8, execute: killer)
    let data = pipe.fileHandleForReading.readDataToEndOfFile()
    process.waitUntilExit()
    killer.cancel()
    return process.terminationStatus == 0 ? String(data: data, encoding: .utf8) : nil
}
