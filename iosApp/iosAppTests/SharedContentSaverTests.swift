import XCTest
import UniformTypeIdentifiers

final class SharedContentSaverTests: XCTestCase {

    private final class StubExtensionContext: NSExtensionContext {
        private let items: [NSExtensionItem]

        init(_ items: [NSExtensionItem]) {
            self.items = items
            super.init()
        }

        override var inputItems: [Any] { items }
    }

    private var containerURL: URL!
    private var filesDir: URL!
    private var descriptorURL: URL!
    private var tempDir: URL!
    private var preexistingFiles: Set<String> = []
    private var preexistingDescriptor: Data?

    private let vcardBytes = Data("BEGIN:VCARD\nVERSION:3.0\nFN:Ada Vance\nEND:VCARD\n".utf8)
    private let movieBytes = Data([0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x6D, 0x70, 0x34, 0x32])
    private let pngBytes = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D])
    private let opaqueBytes = Data([0x7F, 0x21, 0x03, 0xEE, 0x00, 0x91, 0xAB, 0x5C])

    override func setUpWithError() throws {
        try super.setUpWithError()

        containerURL = try XCTUnwrap(
            FileManager.default.containerURL(
                forSecurityApplicationGroupIdentifier: SharedContentSaver.appGroupId
            ),
            "host app must carry the \(SharedContentSaver.appGroupId) App Group entitlement"
        )
        filesDir = containerURL.appendingPathComponent(SharedContentSaver.sharedFilesDir)
        descriptorURL = containerURL.appendingPathComponent(SharedContentSaver.sharedContentFile)

        preexistingFiles = Set((try? FileManager.default.contentsOfDirectory(atPath: filesDir.path)) ?? [])
        preexistingDescriptor = try? Data(contentsOf: descriptorURL)

        tempDir = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("shared_content_saver_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        let current = (try? FileManager.default.contentsOfDirectory(atPath: filesDir.path)) ?? []
        for name in current where !preexistingFiles.contains(name) {
            try? FileManager.default.removeItem(at: filesDir.appendingPathComponent(name))
        }
        if let preexistingDescriptor {
            try preexistingDescriptor.write(to: descriptorURL)
        } else {
            try? FileManager.default.removeItem(at: descriptorURL)
        }
        try? FileManager.default.removeItem(at: tempDir)

        try super.tearDownWithError()
    }

    // MARK: - Data-vended attachments

    func testMovieVendedAsDataIsSaved() throws {
        let descriptor = try save([dataProvider(movieBytes, .mpeg4Movie)])

        XCTAssertEqual(descriptor.contentType, "VIDEO")
        XCTAssertEqual(descriptor.mimeTypes, ["video/mp4"])
        let name = try XCTUnwrap(descriptor.fileNames.first)
        XCTAssertEqual(descriptor.fileNames.count, 1)
        XCTAssertTrue(name.hasPrefix("share_"))
        XCTAssertTrue(name.hasSuffix(".mp4"))
        XCTAssertEqual(try writtenBytes(descriptor), movieBytes)
    }

    func testMovieVendedAsDataFallsBackToMovWhenTypeOffersNoExtension() throws {
        XCTAssertNil(UTType.movie.preferredFilenameExtension)

        let descriptor = try save([dataProvider(movieBytes, .movie)])

        XCTAssertEqual(descriptor.contentType, "VIDEO")
        XCTAssertEqual(descriptor.mimeTypes, ["video/quicktime"])
        XCTAssertTrue(try XCTUnwrap(descriptor.fileNames.first).hasSuffix(".mov"))
        XCTAssertEqual(try writtenBytes(descriptor), movieBytes)
    }

    func testGenericFileVendedAsDataIsSaved() throws {
        let descriptor = try save([dataProvider(vcardBytes, .vCard)])

        XCTAssertEqual(descriptor.contentType, "FILE")
        XCTAssertEqual(descriptor.mimeTypes, ["text/vcard"])
        XCTAssertEqual(descriptor.fileNames.count, 1)
        XCTAssertTrue(try XCTUnwrap(descriptor.fileNames.first).hasSuffix(".vcf"))
        XCTAssertEqual(try writtenBytes(descriptor), vcardBytes)
    }

    func testGenericFileVendedAsDataFallsBackToDatWhenTypeOffersNoExtension() throws {
        XCTAssertNil(UTType.data.preferredFilenameExtension)

        let descriptor = try save([dataProvider(opaqueBytes, .data)])

        XCTAssertEqual(descriptor.contentType, "FILE")
        XCTAssertEqual(descriptor.mimeTypes, ["application/octet-stream"])
        XCTAssertTrue(try XCTUnwrap(descriptor.fileNames.first).hasSuffix(".dat"))
        XCTAssertEqual(try writtenBytes(descriptor), opaqueBytes)
    }

    func testTextAlongsideDataFileKeepsBoth() throws {
        let descriptor = try save([dataProvider(vcardBytes, .vCard)], text: "look at this")

        XCTAssertEqual(descriptor.contentType, "MIXED")
        XCTAssertEqual(descriptor.text, "look at this")
        XCTAssertEqual(descriptor.fileNames.count, 1)
        XCTAssertEqual(descriptor.mimeTypes, ["text/vcard"])
    }

    // MARK: - Extension derivation

    func testSuggestedNameExtensionWinsOverRegisteredType() throws {
        let descriptor = try save([dataProvider(vcardBytes, .vCard, suggestedName: "report.pdf")])

        XCTAssertEqual(descriptor.mimeTypes, ["application/pdf"])
        XCTAssertTrue(try XCTUnwrap(descriptor.fileNames.first).hasSuffix(".pdf"))
    }

    func testRegisteredTypeSuppliesExtensionWhenSuggestedNameHasNone() throws {
        let descriptor = try save([dataProvider(vcardBytes, .vCard, suggestedName: "Ada Vance")])

        XCTAssertEqual(descriptor.mimeTypes, ["text/vcard"])
        XCTAssertTrue(try XCTUnwrap(descriptor.fileNames.first).hasSuffix(".vcf"))
    }

    func testSuggestedNameCannotEscapeTheSharedFilesDirectory() throws {
        let descriptor = try save([
            dataProvider(vcardBytes, .vCard, suggestedName: "../../../../escaped.vcf")
        ])

        let name = try XCTUnwrap(descriptor.fileNames.first)
        XCTAssertFalse(name.contains("/"))
        XCTAssertFalse(name.contains(".."))
        XCTAssertTrue(name.hasPrefix("share_"))
        XCTAssertTrue(name.hasSuffix(".vcf"))

        let written = filesDir.appendingPathComponent(name).standardizedFileURL
        XCTAssertEqual(written.deletingLastPathComponent().path, filesDir.standardizedFileURL.path)
        XCTAssertTrue(FileManager.default.fileExists(atPath: written.path))
        XCTAssertFalse(
            FileManager.default.fileExists(
                atPath: containerURL.appendingPathComponent("escaped.vcf").path
            )
        )
    }

    // MARK: - URL-vended attachments

    func testMovieVendedAsURLIsCopied() throws {
        let source = try tempFile("clip.mp4", movieBytes)
        let descriptor = try save([urlProvider(source, .mpeg4Movie)])

        XCTAssertEqual(descriptor.contentType, "VIDEO")
        XCTAssertEqual(descriptor.mimeTypes, ["video/mp4"])
        XCTAssertTrue(try XCTUnwrap(descriptor.fileNames.first).hasSuffix(".mp4"))
        XCTAssertEqual(try writtenBytes(descriptor), movieBytes)
    }

    func testGenericFileVendedAsURLIsCopied() throws {
        let source = try tempFile("contact.vcf", vcardBytes)
        let descriptor = try save([urlProvider(source, .vCard)])

        XCTAssertEqual(descriptor.contentType, "FILE")
        XCTAssertEqual(descriptor.mimeTypes, ["text/vcard"])
        XCTAssertTrue(try XCTUnwrap(descriptor.fileNames.first).hasSuffix(".vcf"))
        XCTAssertEqual(try writtenBytes(descriptor), vcardBytes)
    }

    func testImageVendedAsURLIsCopied() throws {
        let source = try tempFile("photo.png", pngBytes)
        let descriptor = try save([urlProvider(source, .png)])

        XCTAssertEqual(descriptor.contentType, "IMAGE")
        XCTAssertEqual(descriptor.mimeTypes, ["image/png"])
        XCTAssertTrue(try XCTUnwrap(descriptor.fileNames.first).hasSuffix(".png"))
        XCTAssertEqual(try writtenBytes(descriptor), pngBytes)
    }

    func testImageVendedAsDataIsSniffedFromMagicBytes() throws {
        let descriptor = try save([dataProvider(pngBytes, .png)])

        XCTAssertEqual(descriptor.contentType, "IMAGE")
        XCTAssertEqual(descriptor.mimeTypes, ["image/png"])
        XCTAssertTrue(try XCTUnwrap(descriptor.fileNames.first).hasSuffix(".png"))
        XCTAssertEqual(try writtenBytes(descriptor), pngBytes)
    }

    // MARK: - Helpers

    private func save(
        _ providers: [NSItemProvider],
        text: String? = nil,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws -> SharedContentSaver.ContentDescriptor {
        let item = NSExtensionItem()
        item.attachments = providers
        if let text {
            item.attributedContentText = NSAttributedString(string: text)
        }

        var saved = false
        let finished = expectation(description: "SharedContentSaver.save")
        SharedContentSaver.save(
            extensionContext: StubExtensionContext([item]),
            conversationId: "conversation-under-test"
        ) { success in
            saved = success
            finished.fulfill()
        }
        wait(for: [finished], timeout: 10)
        XCTAssertTrue(saved, "save() reported failure", file: file, line: line)

        return try JSONDecoder().decode(
            SharedContentSaver.ContentDescriptor.self,
            from: Data(contentsOf: descriptorURL)
        )
    }

    private func writtenBytes(_ descriptor: SharedContentSaver.ContentDescriptor) throws -> Data {
        let name = try XCTUnwrap(descriptor.fileNames.first)
        return try Data(contentsOf: filesDir.appendingPathComponent(name))
    }

    private func dataProvider(
        _ bytes: Data,
        _ type: UTType,
        suggestedName: String? = nil
    ) -> NSItemProvider {
        let provider = NSItemProvider(item: bytes as NSData, typeIdentifier: type.identifier)
        provider.suggestedName = suggestedName
        return provider
    }

    private func urlProvider(_ url: URL, _ type: UTType) -> NSItemProvider {
        NSItemProvider(item: url as NSURL, typeIdentifier: type.identifier)
    }

    private func tempFile(_ name: String, _ bytes: Data) throws -> URL {
        let url = tempDir.appendingPathComponent(name)
        try bytes.write(to: url)
        return url
    }
}
