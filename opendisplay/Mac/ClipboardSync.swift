import AppKit

/// Plain-text clipboard sync (`clip` cap, both directions). Off by default:
/// copying text between machines is a privacy decision the user makes.
final class ClipboardSync {
    static let defaultsKey = "clipboardSync"
    static let maxCharacters = 256 * 1024
    /// Control frames are capped at 1 MiB on read; leave room for the envelope.
    private static let maxUTF8Bytes = 900_000

    static var isEnabled: Bool { UserDefaults.standard.bool(forKey: defaultsKey) }

    private let pasteboard = NSPasteboard.general
    private let send: (String) -> Void
    private var timer: DispatchSourceTimer?
    private var lastChangeCount: Int
    // Last text we set or sent; skipped when it comes back (echo loop).
    private var lastText: String?

    init(send: @escaping (String) -> Void) {
        self.send = send
        lastChangeCount = pasteboard.changeCount
    }

    /// Poll about every 500 ms: NSPasteboard has no change notification.
    func start(on queue: DispatchQueue) {
        guard timer == nil else { return }
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .milliseconds(500), repeating: .milliseconds(500))
        t.setEventHandler { [weak self] in self?.poll() }
        t.resume()
        timer = t
    }

    func stop() {
        timer?.cancel()
        timer = nil
    }

    /// Tablet -> Mac.
    func receive(_ text: String) {
        guard Self.isEnabled, text.count <= Self.maxCharacters else { return }
        lastText = text
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        lastChangeCount = pasteboard.changeCount
    }

    private func poll() {
        let count = pasteboard.changeCount
        guard count != lastChangeCount else { return }
        lastChangeCount = count
        guard Self.isEnabled else { return }
        let types = pasteboard.types ?? []
        // Password managers mark their copies; those never leave the Mac.
        guard !types.contains(NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")),
              !types.contains(NSPasteboard.PasteboardType("org.nspasteboard.TransientType")),
              let text = pasteboard.string(forType: .string), !text.isEmpty,
              text != lastText,
              text.count <= Self.maxCharacters,
              text.utf8.count <= Self.maxUTF8Bytes else { return }
        lastText = text
        send(text)
    }
}
