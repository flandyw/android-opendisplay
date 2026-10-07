import XCTest
import CoreMedia

final class VideoPayloadTests: XCTestCase {
    func testTelemetryAndMultipleSlicesConvertToAVCC() {
        let prefix = Data(#"{"cap":123,"snd":456}"#.utf8)
        var frame = prefix
        frame.append(contentsOf: [0, 0, 0, 1, 0x65, 2, 3, 0, 0, 0, 1, 0x41, 4])
        let parsed = AnnexBPayload(frame)
        XCTAssertEqual(parsed.prefix, 0..<prefix.count)
        XCTAssertEqual(parsed.nalUnits.count, 2)
        var output = Data(count: 13)
        XCTAssertTrue(output.withUnsafeMutableBytes {
            AnnexBPayload.writeAVCC(from: frame, nalUnits: parsed.nalUnits, into: $0)
        })
        XCTAssertEqual(output, Data([0, 0, 0, 3, 0x65, 2, 3, 0, 0, 0, 2, 0x41, 4]))
    }

    func testEmptyUnitsAndEmulationPreventionBytes() {
        let frame = Data([0, 0, 0, 1, 0, 0, 0, 1, 0x41, 0, 0, 3, 1, 0, 0, 0, 1])
        let parsed = AnnexBPayload(frame)
        XCTAssertNil(parsed.prefix)
        XCTAssertEqual(parsed.nalUnits, [8..<13])
        XCTAssertTrue(AnnexBPayload(Data([0, 0, 0])).nalUnits.isEmpty)
        XCTAssertTrue(AnnexBPayload(Data()).nalUnits.isEmpty)
    }

    func testSlicedDataUsesRelativeOffsets() {
        let full = Data([99, 99, 0, 0, 0, 1, 0x26, 1, 42])
        let frame = full.dropFirst(2)
        XCTAssertNotEqual(frame.startIndex, 0)
        let parsed = AnnexBPayload(frame)
        XCTAssertEqual(parsed.nalUnits, [4..<7])
        var output = Data(count: 7)
        XCTAssertTrue(output.withUnsafeMutableBytes {
            AnnexBPayload.writeAVCC(from: frame, nalUnits: parsed.nalUnits, into: $0)
        })
        XCTAssertEqual(output, Data([0, 0, 0, 3, 0x26, 1, 42]))
    }

    func testRejectsInvalidDestinationAndRanges() {
        let frame = Data([0x65, 2, 3])
        var output = Data(count: 6)
        XCTAssertFalse(output.withUnsafeMutableBytes {
            AnnexBPayload.writeAVCC(from: frame, nalUnits: [0..<3], into: $0)
        })
        output = Data(count: 8)
        XCTAssertFalse(output.withUnsafeMutableBytes {
            AnnexBPayload.writeAVCC(from: frame, nalUnits: [0..<4], into: $0)
        })
    }

    func testWritesIntoCoreMediaOwnedMemory() throws {
        let frame = Data([0, 0, 0, 1, 0x65, 2, 3])
        let parsed = AnnexBPayload(frame)
        var block: CMBlockBuffer?
        XCTAssertEqual(CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: 7,
            blockAllocator: kCFAllocatorDefault, customBlockSource: nil,
            offsetToData: 0, dataLength: 7, flags: kCMBlockBufferAssureMemoryNowFlag,
            blockBufferOut: &block), noErr)
        let ownedBlock = try XCTUnwrap(block)
        var pointer: UnsafeMutablePointer<Int8>?
        XCTAssertEqual(CMBlockBufferGetDataPointer(ownedBlock, atOffset: 0,
            lengthAtOffsetOut: nil, totalLengthOut: nil, dataPointerOut: &pointer), noErr)
        let destination = try XCTUnwrap(pointer)
        XCTAssertTrue(AnnexBPayload.writeAVCC(from: frame, nalUnits: parsed.nalUnits,
            into: UnsafeMutableRawBufferPointer(start: destination, count: 7)))
        XCTAssertEqual(Data(bytes: destination, count: 7), Data([0, 0, 0, 3, 0x65, 2, 3]))
    }

    func testBulkScannerMatchesReferenceAcrossRandomDataAndZeroRuns() {
        var state: UInt32 = 1234
        for length in 0..<256 {
            var bytes: [UInt8] = []
            for i in 0..<length {
                state = state &* 1664525 &+ 1013904223
                bytes.append(i % 7 < 4 ? 0 : UInt8(truncatingIfNeeded: state >> 24))
                if i % 23 == 0 { bytes.append(contentsOf: [0, 0, 0, 1]) }
            }
            var expected: [Range<Int>] = []
            var first: Int?
            var start: Int?
            var i = 0
            while i + 4 <= bytes.count {
                if Array(bytes[i..<(i + 4)]) == [0, 0, 0, 1] {
                    if first == nil { first = i }
                    if let start, start < i { expected.append(start..<i) }
                    start = i + 4
                    i += 4
                } else { i += 1 }
            }
            if let start, start < bytes.count { expected.append(start..<bytes.count) }
            let parsed = AnnexBPayload(Data(bytes))
            XCTAssertEqual(parsed.nalUnits, expected, "length \(length)")
            XCTAssertEqual(parsed.prefix, first.flatMap { $0 > 0 ? 0..<$0 : nil })
        }
    }
}
