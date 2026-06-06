import Foundation

/// Model mirroring KMP ShareableConversation for the conversation picker.
struct ShareableConversationSwift: Codable, Identifiable {
    let id: String
    let displayName: String
    let avatarInitials: String
    let isGroup: Bool
    let participantCount: Int
    let lastMessageTimestamp: Int64
    let avatarUrl: String?
}

/// Cache container mirroring KMP ShareConversationCacheData.
struct ShareConversationCacheSwift: Codable {
    let conversations: [ShareableConversationSwift]
    let updatedAt: Int64
    let userDomain: String
    /// Optional so caches written before this field existed still decode.
    let momentsActivated: Bool?
}

/// Reads the conversation cache from the App Group container.
/// The main app writes this via KMP ShareConversationCacheWriter.
struct ShareConversationCacheReader {

    static let appGroupId = Bundle.main.infoDictionary?["AppGroupIdentifier"] as? String ?? "group.id.homebase.feed"
    static let cacheFileName = "share_conversation_cache.json"

    static func load() -> (conversations: [ShareableConversationSwift], updatedAt: Date?, momentsActivated: Bool) {
        guard let containerURL = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupId)
        else {
            return ([], nil, false)
        }

        let cacheURL = containerURL.appendingPathComponent(cacheFileName)

        guard let data = try? Data(contentsOf: cacheURL) else {
            return ([], nil, false)
        }

        guard let cache = try? JSONDecoder().decode(ShareConversationCacheSwift.self, from: data)
        else {
            return ([], nil, false)
        }

        let date = Date(timeIntervalSince1970: TimeInterval(cache.updatedAt) / 1000.0)
        return (cache.conversations, date, cache.momentsActivated ?? false)
    }
}
