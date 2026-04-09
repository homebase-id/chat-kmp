import Foundation
import UniformTypeIdentifiers

/// Saves shared content from the extension context into the App Group container
/// for the main app to pick up and send.
struct SharedContentSaver {

    static let appGroupId = Bundle.main.infoDictionary?["AppGroupIdentifier"] as? String ?? "group.id.homebase.feed"
    static let sharedContentFile = "shared_content.json"
    static let sharedFilesDir = "shared_files"

    /// Content descriptor mirroring KMP SharedContentDescriptor.
    struct ContentDescriptor: Codable {
        let contentType: String
        let text: String?
        let url: String?
        let fileNames: [String]
        let mimeTypes: [String]
        let targetConversationId: String
    }

    static func save(
        extensionContext: NSExtensionContext,
        conversationId: String,
        completion: @escaping (Bool) -> Void
    ) {
        guard let containerURL = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupId)
        else {
            completion(false)
            return
        }

        let filesDir = containerURL.appendingPathComponent(sharedFilesDir)
        try? FileManager.default.createDirectory(at: filesDir, withIntermediateDirectories: true)

        guard let inputItems = extensionContext.inputItems as? [NSExtensionItem] else {
            completion(false)
            return
        }

        var text: String?
        var url: String?
        var fileNames: [String] = []
        var mimeTypes: [String] = []
        let group = DispatchGroup()

        for item in inputItems {
            // Check for text in attributedContentText
            if let attributedText = item.attributedContentText?.string, !attributedText.isEmpty {
                text = attributedText
            }

            guard let attachments = item.attachments else { continue }

            for provider in attachments {
                // Handle URLs
                if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                    group.enter()
                    provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, _ in
                        if let shareURL = item as? URL {
                            if shareURL.isFileURL {
                                // File URL — copy the file
                                if let result = copyFile(from: shareURL, to: filesDir) {
                                    fileNames.append(result.name)
                                    mimeTypes.append(result.mimeType)
                                }
                            } else {
                                url = shareURL.absoluteString
                            }
                        }
                        group.leave()
                    }
                    continue
                }

                // Handle text
                if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                    group.enter()
                    provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { item, _ in
                        if let string = item as? String {
                            text = string
                        }
                        group.leave()
                    }
                    continue
                }

                // Handle images
                if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                    group.enter()
                    provider.loadItem(forTypeIdentifier: UTType.image.identifier, options: nil) { item, _ in
                        if let imageURL = item as? URL {
                            if let result = copyFile(from: imageURL, to: filesDir) {
                                fileNames.append(result.name)
                                mimeTypes.append(result.mimeType)
                            }
                        } else if let imageData = item as? Data {
                            let name = "share_\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
                            let destURL = filesDir.appendingPathComponent(name)
                            try? imageData.write(to: destURL)
                            fileNames.append(name)
                            mimeTypes.append("image/jpeg")
                        }
                        group.leave()
                    }
                    continue
                }

                // Handle videos
                if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                    group.enter()
                    provider.loadItem(forTypeIdentifier: UTType.movie.identifier, options: nil) { item, _ in
                        if let videoURL = item as? URL {
                            if let result = copyFile(from: videoURL, to: filesDir) {
                                fileNames.append(result.name)
                                mimeTypes.append(result.mimeType)
                            }
                        }
                        group.leave()
                    }
                    continue
                }

                // Handle generic files (data)
                if provider.hasItemConformingToTypeIdentifier(UTType.data.identifier) {
                    group.enter()
                    provider.loadItem(forTypeIdentifier: UTType.data.identifier, options: nil) { item, _ in
                        if let fileURL = item as? URL {
                            if let result = copyFile(from: fileURL, to: filesDir) {
                                fileNames.append(result.name)
                                mimeTypes.append(result.mimeType)
                            }
                        }
                        group.leave()
                    }
                    continue
                }
            }
        }

        group.notify(queue: .main) {
            // Determine content type
            let contentType: String
            if !fileNames.isEmpty && (text != nil || url != nil) {
                contentType = "MIXED"
            } else if !fileNames.isEmpty {
                let hasImage = mimeTypes.contains { $0.hasPrefix("image/") }
                let hasVideo = mimeTypes.contains { $0.hasPrefix("video/") }
                if hasImage && !hasVideo { contentType = "IMAGE" }
                else if hasVideo && !hasImage { contentType = "VIDEO" }
                else { contentType = "FILE" }
            } else if url != nil {
                contentType = "URL"
            } else {
                contentType = "TEXT"
            }

            let descriptor = ContentDescriptor(
                contentType: contentType,
                text: text,
                url: url,
                fileNames: fileNames,
                mimeTypes: mimeTypes,
                targetConversationId: conversationId
            )

            // Write descriptor JSON
            if let jsonData = try? JSONEncoder().encode(descriptor) {
                let descriptorURL = containerURL.appendingPathComponent(sharedContentFile)
                try? jsonData.write(to: descriptorURL)
                completion(true)
            } else {
                completion(false)
            }
        }
    }

    // MARK: - File Helpers

    private struct CopyResult {
        let name: String
        let mimeType: String
    }

    private static func copyFile(from sourceURL: URL, to directory: URL) -> CopyResult? {
        let ext = sourceURL.pathExtension
        let name = "share_\(Int(Date().timeIntervalSince1970 * 1000))_\(Int.random(in: 0...9999)).\(ext)"
        let destURL = directory.appendingPathComponent(name)

        do {
            if FileManager.default.fileExists(atPath: destURL.path) {
                try FileManager.default.removeItem(at: destURL)
            }
            try FileManager.default.copyItem(at: sourceURL, to: destURL)

            let mimeType = mimeTypeForExtension(ext)
            return CopyResult(name: name, mimeType: mimeType)
        } catch {
            return nil
        }
    }

    private static func mimeTypeForExtension(_ ext: String) -> String {
        if let utType = UTType(filenameExtension: ext) {
            return utType.preferredMIMEType ?? "application/octet-stream"
        }
        return "application/octet-stream"
    }
}
