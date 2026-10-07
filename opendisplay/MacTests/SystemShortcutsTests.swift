import XCTest

final class SystemShortcutsTests: XCTestCase {
    func testMissingEntryMeansEnabled() {
        XCTAssertTrue(SystemShortcuts.isEnabled(.missionControl, in: nil))
        XCTAssertTrue(SystemShortcuts.isEnabled(.missionControl, in: [:]))
    }

    func testExplicitlyDisabledEntries() {
        let table: [String: Any] = [
            "32": ["enabled": false, "value": ["type": "standard"]],
            "79": ["enabled": 0],
            "81": ["enabled": true],
        ]
        XCTAssertFalse(SystemShortcuts.isEnabled(.missionControl, in: table))
        XCTAssertFalse(SystemShortcuts.isEnabled(.moveLeftASpace, in: table))
        XCTAssertTrue(SystemShortcuts.isEnabled(.moveRightASpace, in: table))
        XCTAssertEqual(SystemShortcuts.disabled(in: table), [.missionControl, .moveLeftASpace])
    }

    func testCtrlArrowMapsToShortcut() {
        XCTAssertEqual(SystemShortcuts.hotKey(forKeyCode: 126, mods: 2), .missionControl)
        XCTAssertEqual(SystemShortcuts.hotKey(forKeyCode: 124, mods: 2), .moveRightASpace)
        XCTAssertNil(SystemShortcuts.hotKey(forKeyCode: 126, mods: 0))
        XCTAssertNil(SystemShortcuts.hotKey(forKeyCode: 126, mods: 2 | 1))   // Ctrl+Shift+Up is something else
        XCTAssertNil(SystemShortcuts.hotKey(forKeyCode: 0, mods: 2))
    }

    func testMissionControlIsOpenedOnlyWhenItsShortcutIsOff() {
        let off: [String: Any] = ["32": ["enabled": false]]
        XCTAssertTrue(SystemShortcuts.shouldOpenMissionControl(keyCode: 126, mods: 2, table: off))
        XCTAssertFalse(SystemShortcuts.shouldOpenMissionControl(keyCode: 126, mods: 2, table: [:]))
        XCTAssertFalse(SystemShortcuts.shouldOpenMissionControl(keyCode: 125, mods: 2, table: off))
    }
}
