import XCTest

final class AdbDevicesTests: XCTestCase {
    private let sample = """
    * daemon not running; starting now at tcp:5037
    * daemon started successfully
    List of devices attached
    R58M123ABC             device usb:1-1 product:tangorpro model:Pixel_Tablet device:tangorpro transport_id:3
    emulator-5554          device product:sdk_gphone64 model:sdk_gphone64 device:emu transport_id:1
    0123456789ABCDEF       unauthorized usb:1-2 transport_id:4
    192.168.1.5:5555       device product:husky model:Pixel_8_Pro device:husky transport_id:5

    """

    func testParsesDevicesAndSkipsChatterAndEmulators() {
        let devices = AdbDevices.parse(sample)
        XCTAssertEqual(devices.map(\.serial), ["R58M123ABC", "0123456789ABCDEF", "192.168.1.5:5555"])
    }

    func testStateModelAndTransport() {
        let devices = AdbDevices.parse(sample)
        XCTAssertTrue(devices[0].isReady)
        XCTAssertTrue(devices[0].overUSB)
        XCTAssertEqual(devices[0].displayName, "Pixel Tablet")
        XCTAssertFalse(devices[1].isReady)          // waiting for the debugging prompt
        XCTAssertEqual(devices[1].displayName, "0123456789ABCDEF")
        XCTAssertFalse(devices[2].overUSB)          // wireless debugging
    }

    func testEmptyListYieldsNothing() {
        XCTAssertEqual(AdbDevices.parse("List of devices attached\n\n"), [])
        XCTAssertEqual(AdbDevices.parse(""), [])
    }

    func testForwardedPort() {
        XCTAssertEqual(AdbDevices.parseForwardedPort("54321\n"), 54321)
        XCTAssertNil(AdbDevices.parseForwardedPort("error: closed"))
    }

    func testCandidatePathsPreferThePathVariable() {
        let paths = AdbDevices.candidatePaths(environmentPath: "/a:/b", home: "/Users/me")
        XCTAssertEqual(Array(paths.prefix(2)), ["/a/adb", "/b/adb"])
        XCTAssertTrue(paths.contains("/Users/me/Library/Android/sdk/platform-tools/adb"))
    }
}
