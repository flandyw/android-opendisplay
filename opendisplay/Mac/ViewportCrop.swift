import Foundation

/// The part of the desktop a zoomed receiver is showing (`viewport` message).
///
/// The receiver sends the visible rect in normalized desktop space while the
/// user pinches. Cropping the capture to it, and encoding the crop at the full
/// stream size, makes zoom real detail instead of magnified compression.
enum ViewportCrop {
    /// Smallest visible fraction honoured, so a bad message cannot ask for a
    /// sliver that scales to a blur.
    static let minimumFraction = 0.02

    /// A rect this close to the whole desktop is just the whole desktop.
    static let fullThreshold = 0.995

    /// The wire rect clamped into the desktop, or nil when it covers (nearly)
    /// everything or is not usable.
    static func normalized(x: Double, y: Double, w: Double, h: Double) -> CGRect? {
        guard x.isFinite, y.isFinite, w.isFinite, h.isFinite else { return nil }
        let width = min(max(w, minimumFraction), 1)
        let height = min(max(h, minimumFraction), 1)
        if width >= fullThreshold && height >= fullThreshold { return nil }
        let left = min(max(x, 0), 1 - width)
        let top = min(max(y, 0), 1 - height)
        return CGRect(x: left, y: top, width: width, height: height)
    }

    /// `SCStreamConfiguration.sourceRect` for a crop: display points, origin at
    /// the display's top-left. `.zero` is ScreenCaptureKit's "whole display".
    static func sourceRect(for crop: CGRect?, displaySize: CGSize) -> CGRect {
        guard let crop, displaySize.width > 0, displaySize.height > 0 else { return .zero }
        return CGRect(x: crop.minX * displaySize.width,
                      y: crop.minY * displaySize.height,
                      width: crop.width * displaySize.width,
                      height: crop.height * displaySize.height)
    }
}
