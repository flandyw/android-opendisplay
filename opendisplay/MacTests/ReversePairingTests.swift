import XCTest

final class ReversePairingTests: XCTestCase {
    func testTokensAreLongAndDifferent() {
        let a = ReversePairing.newToken()
        let b = ReversePairing.newToken()
        XCTAssertNotEqual(a, b)
        XCTAssertGreaterThanOrEqual(a.count, 43)   // 32 bytes of base64
    }

    func testMatchingRequiresTheExactToken() {
        XCTAssertTrue(ReversePairing.matches("abc", "abc"))
        XCTAssertFalse(ReversePairing.matches("abd", "abc"))
        XCTAssertFalse(ReversePairing.matches("ab", "abc"))
        XCTAssertFalse(ReversePairing.matches("abcd", "abc"))
        XCTAssertFalse(ReversePairing.matches(nil, "abc"))
        XCTAssertFalse(ReversePairing.matches("abc", nil))
        XCTAssertFalse(ReversePairing.matches("", ""))   // an empty token never pairs
    }

    func testPairedDeviceWithItsTokenIsAdmitted() {
        let paired = ["tablet-1": "secret"]
        XCTAssertEqual(ReversePairing.decide(deviceID: "tablet-1", presentedToken: "secret", paired: paired), .admit)
    }

    func testKnownIDWithoutTheTokenIsAskedAbout() {
        // The id is public in Bonjour, so knowing it proves nothing.
        let paired = ["tablet-1": "secret"]
        XCTAssertEqual(ReversePairing.decide(deviceID: "tablet-1", presentedToken: nil, paired: paired), .ask)
        XCTAssertEqual(ReversePairing.decide(deviceID: "tablet-1", presentedToken: "guess", paired: paired), .ask)
    }

    func testUnknownOrMissingIDIsAskedAbout() {
        XCTAssertEqual(ReversePairing.decide(deviceID: "other", presentedToken: "secret", paired: ["tablet-1": "secret"]), .ask)
        XCTAssertEqual(ReversePairing.decide(deviceID: nil, presentedToken: "secret", paired: ["tablet-1": "secret"]), .ask)
    }
}
