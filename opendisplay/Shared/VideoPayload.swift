import Foundation

/// Offsets into one wire payload. Keeping slices as ranges avoids allocating
/// and copying each NAL before copying it again into CoreMedia's AVCC buffer.
struct AnnexBPayload {
    let prefix: Range<Int>?
    let nalUnits: [Range<Int>]

    init(_ data: Data) {
        var ranges: [Range<Int>] = []
        var firstStartCode: Int?
        data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
            guard bytes.count >= 4, let base = bytes.baseAddress else { return }
            let pointer = base.assumingMemoryBound(to: UInt8.self)
            var start: Int?
            var i = 0
            while i + 4 <= bytes.count {
                // Most compressed bytes are nonzero. libc searches those
                // spans in bulk instead of examining every byte in Swift.
                guard let zero = memchr(base.advanced(by: i), 0, bytes.count - i - 3) else { break }
                i = pointer.distance(to: zero.assumingMemoryBound(to: UInt8.self))
                if pointer[i + 1] == 0, pointer[i + 2] == 0, pointer[i + 3] == 1 {
                    if firstStartCode == nil { firstStartCode = i }
                    if let start, start < i { ranges.append(start..<i) }
                    start = i + 4
                    i += 4
                } else {
                    i += 1
                }
            }
            if let start, start < bytes.count { ranges.append(start..<bytes.count) }
        }
        prefix = firstStartCode.flatMap { $0 > 0 ? 0..<$0 : nil }
        nalUnits = ranges
    }

    /// Write directly into the destination owned by CoreMedia.
    static func writeAVCC(from data: Data, nalUnits: [Range<Int>],
                          into output: UnsafeMutableRawBufferPointer) -> Bool {
        let size = nalUnits.reduce(0) { $0 + $1.count + 4 }
        guard output.count == size, let destination = output.baseAddress,
              nalUnits.allSatisfy({ !$0.isEmpty && $0.lowerBound >= 0 && $0.upperBound <= data.count })
        else { return false }
        data.withUnsafeBytes { (source: UnsafeRawBufferPointer) in
            var offset = 0
            for range in nalUnits {
                var length = UInt32(range.count).bigEndian
                withUnsafeBytes(of: &length) { header in
                    destination.advanced(by: offset).copyMemory(from: header.baseAddress!, byteCount: 4)
                }
                offset += 4
                destination.advanced(by: offset).copyMemory(
                    from: source.baseAddress!.advanced(by: range.lowerBound), byteCount: range.count)
                offset += range.count
            }
        }
        return true
    }
}
