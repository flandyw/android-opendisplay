import XCTest

final class ViewportCropTests: XCTestCase {
    func testWholeDesktopIsNoCrop() {
        XCTAssertNil(ViewportCrop.normalized(x: 0, y: 0, w: 1, h: 1))
        XCTAssertNil(ViewportCrop.normalized(x: 0, y: 0, w: 0.999, h: 0.998))
    }

    func testZoomedRectPassesThrough() {
        let crop = ViewportCrop.normalized(x: 0.25, y: 0.1, w: 0.5, h: 0.4)
        XCTAssertEqual(crop, CGRect(x: 0.25, y: 0.1, width: 0.5, height: 0.4))
    }

    func testRectIsClampedInsideTheDesktop() {
        let crop = ViewportCrop.normalized(x: 0.9, y: -0.2, w: 0.5, h: 0.5)
        XCTAssertEqual(crop, CGRect(x: 0.5, y: 0, width: 0.5, height: 0.5))
    }

    func testTinyRectIsWidenedToTheMinimum() {
        let crop = ViewportCrop.normalized(x: 0.4, y: 0.4, w: 0, h: 0.001)
        XCTAssertEqual(Double(crop?.width ?? 0), ViewportCrop.minimumFraction, accuracy: 1e-9)
        XCTAssertEqual(Double(crop?.height ?? 0), ViewportCrop.minimumFraction, accuracy: 1e-9)
    }

    func testNonFiniteValuesAreRejected() {
        XCTAssertNil(ViewportCrop.normalized(x: .nan, y: 0, w: 0.5, h: 0.5))
        XCTAssertNil(ViewportCrop.normalized(x: 0, y: 0, w: .infinity, h: 0.5))
    }

    func testSourceRectScalesToDisplayPoints() {
        let rect = ViewportCrop.sourceRect(
            for: CGRect(x: 0.25, y: 0.5, width: 0.5, height: 0.25),
            displaySize: CGSize(width: 1280, height: 800))
        XCTAssertEqual(rect, CGRect(x: 320, y: 400, width: 640, height: 200))
    }

    func testNoCropMeansWholeDisplay() {
        XCTAssertEqual(ViewportCrop.sourceRect(for: nil, displaySize: CGSize(width: 1280, height: 800)), .zero)
        XCTAssertEqual(ViewportCrop.sourceRect(for: CGRect(x: 0, y: 0, width: 0.5, height: 0.5),
                                               displaySize: .zero), .zero)
    }
}
