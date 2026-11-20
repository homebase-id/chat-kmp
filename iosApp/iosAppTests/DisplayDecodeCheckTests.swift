import XCTest
@testable import ComposeApp

final class DisplayDecodeCheckTests: XCTestCase {
    func testPhotosShowAtTheRequestedSizeUprightAndWithoutAFullSizeBitmap() {
        let done = expectation(description: "display-decode checks finished")
        var report: CheckReport?
        DisplayDecodeChecks.shared.runChecks { report = $0; done.fulfill() }
        wait(for: [done], timeout: 300)
        report?.measurements.forEach { print("measured: \($0)") }
        let failures = report?.failures ?? ["no report"]
        XCTAssertTrue(failures.isEmpty, failures.joined(separator: "\n"))
    }

    func testPhotosWithAGainMapShowInHdrInTheViewer() {
        let done = expectation(description: "HDR viewer checks finished")
        var report: CheckReport?
        HdrViewerChecks.shared.runChecks { report = $0; done.fulfill() }
        wait(for: [done], timeout: 300)
        report?.measurements.forEach { print("measured: \($0)") }
        let failures = report?.failures ?? ["no report"]
        XCTAssertTrue(failures.isEmpty, failures.joined(separator: "\n"))
    }
}
