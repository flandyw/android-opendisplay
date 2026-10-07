import Foundation
import CoreMedia

@main struct PayloadBench {
    static func block(_ size: Int) -> CMBlockBuffer {
        var result: CMBlockBuffer?
        precondition(CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault,
            memoryBlock: nil, blockLength: size, blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil, offsetToData: 0, dataLength: size,
            flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &result) == noErr)
        return result!
    }
    static func old(_ data: Data) -> CMBlockBuffer {
        var nalus: [Data] = []
        data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
            var start: Int?
            var i = 0
            while i + 4 <= bytes.count {
                if bytes[i] == 0, bytes[i+1] == 0, bytes[i+2] == 0, bytes[i+3] == 1 {
                    if let start, start < i { nalus.append(Data(bytes[start..<i])) }
                    start = i + 4
                    i += 4
                } else { i += 1 }
            }
            if let start, start < bytes.count { nalus.append(Data(bytes[start..<bytes.count])) }
        }
        var avcc = Data(capacity: nalus.reduce(0) { $0 + $1.count + 4 })
        for nalu in nalus {
            var size = UInt32(nalu.count).bigEndian
            avcc.append(Data(bytes: &size, count: 4))
            avcc.append(nalu)
        }
        let result = block(avcc.count)
        avcc.withUnsafeBytes { raw in
            precondition(CMBlockBufferReplaceDataBytes(with: raw.baseAddress!,
                blockBuffer: result, offsetIntoDestination: 0, dataLength: raw.count) == noErr)
        }
        return result
    }
    static func new(_ data: Data) -> CMBlockBuffer {
        let parsed = AnnexBPayload(data)
        let size = parsed.nalUnits.reduce(0) { $0 + $1.count + 4 }
        let result = block(size)
        var pointer: UnsafeMutablePointer<Int8>?
        precondition(CMBlockBufferGetDataPointer(result, atOffset: 0,
            lengthAtOffsetOut: nil, totalLengthOut: nil, dataPointerOut: &pointer) == noErr)
        precondition(AnnexBPayload.writeAVCC(from: data, nalUnits: parsed.nalUnits,
            into: UnsafeMutableRawBufferPointer(start: pointer!, count: size)))
        return result
    }
    static func bytes(_ block: CMBlockBuffer) -> Data {
        var result = Data(count: CMBlockBufferGetDataLength(block))
        result.withUnsafeMutableBytes {
            precondition(CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: $0.count,
                destination: $0.baseAddress!) == noErr)
        }
        return result
    }
    static func time(_ data: Data, iterations: Int, convert: (Data) -> CMBlockBuffer) -> Double {
        var sum = 0
        let start = ProcessInfo.processInfo.systemUptime
        for _ in 0..<iterations {
            autoreleasepool { sum += CMBlockBufferGetDataLength(convert(data)) }
        }
        precondition(sum > 0)
        return (ProcessInfo.processInfo.systemUptime - start) * 1000 / Double(iterations)
    }
    static func main() {
        for total in [32_768, 131_072, 1_048_576] {
            var data = Data()
            for _ in 0..<8 {
                data.append(contentsOf: [0, 0, 0, 1, 0x41])
                var state: UInt32 = 0x12345678
                var zeros = 0
                for _ in 0..<(total / 8 - 1) {
                    state = state &* 1664525 &+ 1013904223
                    let byte = UInt8(truncatingIfNeeded: state >> 24)
                    if zeros >= 2, byte <= 3 { data.append(3); zeros = 0 }
                    data.append(byte)
                    zeros = byte == 0 ? zeros + 1 : 0
                }
            }
            precondition(bytes(old(data)) == bytes(new(data)))
            let iterations = total < 1_000_000 ? 3000 : 500
            // Warm both paths and alternate their order over six measurements.
            _ = time(data, iterations: 30, convert: old)
            _ = time(data, iterations: 30, convert: new)
            var oldTimes: [Double] = [], newTimes: [Double] = []
            for run in 0..<6 {
                if run % 2 == 0 {
                    oldTimes.append(time(data, iterations: iterations, convert: old))
                    newTimes.append(time(data, iterations: iterations, convert: new))
                } else {
                    newTimes.append(time(data, iterations: iterations, convert: new))
                    oldTimes.append(time(data, iterations: iterations, convert: old))
                }
            }
            let oldMs = oldTimes.sorted()[3], newMs = newTimes.sorted()[3]
            print(String(format: "%d bytes: old %.3f ms/frame, new %.3f ms/frame, %.0f%% less CPU time",
                total, oldMs, newMs, (1-newMs/oldMs)*100))
        }
    }
}
