import AppKit
import ImageIO
import UniformTypeIdentifiers

/// Clipboard sync (`clip` cap for text, `clipimg` for images, both
/// directions). Off by default: copying between machines is a privacy
/// decision the user makes.
final class ClipboardSync {
    static let defaultsKey = "clipboardSync"
    static let maxCharacters = 256 * 1024
    /// Control frames are capped at 1 MiB on read; leave room for the envelope.
    private static let maxUTF8Bytes = 900_000
    /// PNG bytes before base64 (which adds a third) must still fit that frame.
    static let maxImageBytes = 600_000

    static var isEnabled: Bool { UserDefaults.standard.bool(forKey: defaultsKey) }

    private let pasteboard = NSPasteboard.general
    private let send: (String) -> Void
    /// Nil when the receiver cannot take images.
    private let sendImage: ((Data) -> Void)?
    private var timer: DispatchSourceTimer?
    private var lastChangeCount: Int
    // Last text we set or sent; skipped when it comes back (echo loop).
    private var lastText: String?
    private var lastImageHash: Int?

    init(send: @escaping (String) -> Void, sendImage: ((Data) -> Void)? = nil) {
        self.send = send
        self.sendImage = sendImage
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

    /// Tablet -> Mac, a PNG image.
    func receiveImage(_ png: Data) {
        guard Self.isEnabled, png.count <= Self.maxImageBytes,
              let image = NSImage(data: png) else { return }
        lastImageHash = png.hashValue
        pasteboard.clearContents()
        pasteboard.writeObjects([image])
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
              !types.contains(NSPasteboard.PasteboardType("org.nspasteboard.TransientType")) else { return }
        if let text = pasteboard.string(forType: .string), !text.isEmpty {
            guard text != lastText,
                  text.count <= Self.maxCharacters,
                  text.utf8.count <= Self.maxUTF8Bytes else { return }
            lastText = text
            send(text)
            return
        }
        guard let sendImage, let png = Self.pngFromPasteboard(pasteboard),
              png.hashValue != lastImageHash else { return }
        lastImageHash = png.hashValue
        sendImage(png)
    }

    /// The pasteboard's image as PNG, shrunk until it fits one control frame.
    private static func pngFromPasteboard(_ pasteboard: NSPasteboard) -> Data? {
        guard let image = NSImage(pasteboard: pasteboard),
              let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }
        return pngData(cgImage, maxBytes: maxImageBytes)
    }

    /// Encodes [image] as PNG, scaling it down by a third at a time until the
    /// result is at most [maxBytes]; nil when it can't get there.
    static func pngData(_ image: CGImage, maxBytes: Int) -> Data? {
        var width = image.width
        var height = image.height
        for _ in 0..<8 {
            if let data = encodePNG(image, width: width, height: height), data.count <= maxBytes {
                return data
            }
            width = width * 7 / 10
            height = height * 7 / 10
            if width < 16 || height < 16 { return nil }
        }
        return nil
    }

    private static func encodePNG(_ image: CGImage, width: Int, height: Int) -> Data? {
        guard let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let scaled = context.makeImage() else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            data as CFMutableData, UTType.png.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, scaled, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }
}
