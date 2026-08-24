import Foundation
import UniformTypeIdentifiers

/// Saves shared content from the extension context into the App Group container
/// for the main app to pick up and send.
struct SharedContentSaver {

    static let appGroupId = Bundle.main.infoDictionary?["AppGroupIdentifier"] as? String ?? "group.id.homebase.feed"
    static let sharedContentFile = "shared_content.json"
    static let sharedFilesDir = "shared_files"

    /// Sentinel `targetConversationId` for a "New Moment" share. The main app's
    /// moment hand-off ignores the target (the `homebase-share://moment` URL is
    /// what disambiguates), but the descriptor field is non-optional, so we
    /// stamp this rather than a real conversation id.
    static let momentTargetId = "__moment__"

    /// Synchronously reports whether the share carries at least one image or
    /// video. Used to gate the "New Moment" option, which requires media.
    static func hasMedia(in extensionContext: NSExtensionContext) -> Bool {
        guard let inputItems = extensionContext.inputItems as? [NSExtensionItem] else { return false }
        for item in inputItems {
            guard let attachments = item.attachments else { continue }
            for provider in attachments {
                if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) ||
                   provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                    return true
                }
            }
        }
        return false
    }

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
                        // A blank vend must not overwrite a real `text`, nor stand in as one:
                        // a blank-but-non-nil text shadows `url` on the Kotlin side and sends an
                        // empty message. Mirrors the attributedContentText guard above (#1097).
                        if let string = item as? String, !string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
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
                            // Sniff the real type from the bytes — never blanket-label as JPEG
                            // (a shared PNG with alpha must keep image/png, etc.). #854.
                            let ext = imageExtensionForData(imageData)
                            let name = "share_\(Int(Date().timeIntervalSince1970 * 1000)).\(ext)"
                            let destURL = filesDir.appendingPathComponent(name)
                            try? imageData.write(to: destURL)
                            fileNames.append(name)
                            mimeTypes.append(mimeTypeForExtension(ext))
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
                        } else if let videoData = item as? Data {
                            if let result = writeData(
                                videoData,
                                from: provider,
                                conformingTo: .movie,
                                fallbackExtension: "mov",
                                to: filesDir
                            ) {
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
                        } else if let fileData = item as? Data {
                            if let result = writeData(
                                fileData,
                                from: provider,
                                conformingTo: .data,
                                fallbackExtension: "dat",
                                to: filesDir
                            ) {
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

    private static func writeData(
        _ data: Data,
        from provider: NSItemProvider,
        conformingTo type: UTType,
        fallbackExtension: String,
        to directory: URL
    ) -> CopyResult? {
        let ext = dataExtension(for: provider, conformingTo: type, fallback: fallbackExtension)
        let name = "share_\(Int(Date().timeIntervalSince1970 * 1000))_\(Int.random(in: 0...9999)).\(ext)"
        let destURL = directory.appendingPathComponent(name)

        do {
            try data.write(to: destURL)
            return CopyResult(name: name, mimeType: mimeTypeForExtension(ext))
        } catch {
            return nil
        }
    }

    /// Only the extension is taken from `suggestedName`: it is cross-process input and must
    /// never become a path component of its own.
    private static func dataExtension(
        for provider: NSItemProvider,
        conformingTo type: UTType,
        fallback: String
    ) -> String {
        if let suggested = provider.suggestedName {
            let ext = (suggested as NSString).pathExtension
            if !ext.isEmpty { return ext }
        }
        for identifier in provider.registeredTypeIdentifiers {
            guard let registered = UTType(identifier),
                  registered.conforms(to: type),
                  let ext = registered.preferredFilenameExtension
            else { continue }
            return ext
        }
        return fallback
    }

    private static func mimeTypeForExtension(_ ext: String) -> String {
        if let utType = UTType(filenameExtension: ext) {
            return utType.preferredMIMEType ?? "application/octet-stream"
        }
        return "application/octet-stream"
    }

    /// Sniff an image file extension from magic bytes. Shared image `Data` carries no
    /// extension or reliable MIME, so we read the signature rather than assume JPEG (#854).
    private static func imageExtensionForData(_ data: Data) -> String {
        let b = [UInt8](data.prefix(16))
        func match(_ sig: [UInt8], at offset: Int = 0) -> Bool {
            guard b.count >= offset + sig.count else { return false }
            for (i, v) in sig.enumerated() where b[offset + i] != v { return false }
            return true
        }
        if match([0x89, 0x50, 0x4E, 0x47]) { return "png" }                       // PNG
        if match([0x47, 0x49, 0x46, 0x38]) { return "gif" }                       // GIF8
        if match([0x52, 0x49, 0x46, 0x46]) && match([0x57, 0x45, 0x42, 0x50], at: 8) { return "webp" } // RIFF…WEBP
        if match([0x66, 0x74, 0x79, 0x70], at: 4) { return "heic" }               // ftyp (HEIF/HEIC family)
        return "jpg"                                                              // FFD8 JPEG / default
    }
}
