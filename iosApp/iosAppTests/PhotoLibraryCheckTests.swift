import XCTest
@testable import ComposeApp

/// Needs full Photos access: CI grants it with `simctl privacy grant photos` before the run.
final class PhotoLibraryCheckTests: XCTestCase {
    func testPhotoLibraryPathsAgainstTheRealLibrary() {
        let done = expectation(description: "Photos-library checks finished")
        var report: CheckReport?
        PhotoLibraryChecks.shared.runChecks { report = $0; done.fulfill() }
        wait(for: [done], timeout: 300)
        let failures = report?.failures ?? ["no report"]
        XCTAssertTrue(failures.isEmpty, failures.joined(separator: "\n"))
    }
}
