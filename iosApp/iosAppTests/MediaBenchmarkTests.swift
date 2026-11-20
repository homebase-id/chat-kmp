import XCTest
@testable import ComposeApp

final class MediaBenchmarkTests: XCTestCase {
    func testMediaBenchmarks() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["MEDIA_BENCH"] == "1", "a benchmark: run with TEST_RUNNER_MEDIA_BENCH=1")
        let done = expectation(description: "media benchmarks finished")
        var report: CheckReport?
        MediaBenchmarks.shared.runBenchmarks { report = $0; done.fulfill() }
        wait(for: [done], timeout: 4 * 3600)
        let failures = report?.failures ?? ["no report"]
        XCTAssertTrue(failures.isEmpty, failures.joined(separator: "\n"))
    }
}
