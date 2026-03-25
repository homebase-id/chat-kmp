import Foundation

/// Model mirroring KMP ShareableConversation for the conversation picker.
struct ShareableConversationSwift: Codable, Identifiable {
    let id: String
    let displayName: String
    let avatarInitials: String
    let isGroup: Bool
    let participantCount: Int
    let lastMessageTimestamp: Int64
}

/// Cache container mirroring KMP ShareConversationCacheData.
struct ShareConversationCacheSwift: Codable {
    let conversations: [ShareableConversationSwift]
    let updatedAt: Int64
    let userDomain: String
}

/// Reads the encrypted conversation cache from the App Group container.
/// The main app writes this via KMP ShareConversationCacheWriter.
struct ShareConversationCacheReader {

    static let appGroupId = "group.id.homebase.chat"
    static let cacheFileName = "share_conversation_cache.enc"
    static let encryptionKeyName = "share_cache_encryption_key"
    static let keychainService = "id.homebase.share"

    static func load() -> (conversations: [ShareableConversationSwift], updatedAt: Date?) {
        guard let containerURL = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupId)
        else {
            return ([], nil)
        }

        let cacheURL = containerURL.appendingPathComponent(cacheFileName)

        guard let encryptedData = try? Data(contentsOf: cacheURL) else {
            return ([], nil)
        }

        // Decrypt
        guard let key = getEncryptionKey(),
              let decryptedData = xorDecrypt(data: encryptedData, key: key)
        else {
            // Try reading as unencrypted JSON fallback
            if let cache = try? JSONDecoder().decode(ShareConversationCacheSwift.self, from: encryptedData) {
                let date = Date(timeIntervalSince1970: TimeInterval(cache.updatedAt) / 1000.0)
                return (cache.conversations, date)
            }
            return ([], nil)
        }

        guard let cache = try? JSONDecoder().decode(ShareConversationCacheSwift.self, from: decryptedData)
        else {
            return ([], nil)
        }

        let date = Date(timeIntervalSince1970: TimeInterval(cache.updatedAt) / 1000.0)
        return (cache.conversations, date)
    }

    // MARK: - Encryption

    private static func getEncryptionKey() -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: encryptionKeyName,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return data
    }

    /// Simple XOR decryption (symmetric with encryption in KMP ShareCacheStorage.native.kt)
    private static func xorDecrypt(data: Data, key: Data) -> Data? {
        guard !key.isEmpty else { return nil }

        var result = Data(count: data.count)
        for i in 0..<data.count {
            result[i] = data[i] ^ key[i % key.count]
        }
        return result
    }
}
