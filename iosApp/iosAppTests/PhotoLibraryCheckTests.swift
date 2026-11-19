import XCTest
@testable import ComposeApp

/// Needs full Photos access: CI grants it with `simctl privacy grant photos` before the run.
final class PhotoLibraryCheckTests: XCTestCase {
    func testPhotoLibraryPathsAgainstTheRealLibrary() async throws {
        let failures = try await PhotoLibraryChecks.shared.run()
        XCTAssertTrue(failures.isEmpty, failures.joined(separator: "\n"))
    }
}
