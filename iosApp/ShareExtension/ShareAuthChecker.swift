import Foundation

/// Checks whether the user is authenticated by looking for the conversation cache
/// in the App Group container. If the cache exists, the main app has written it,
/// meaning the user is signed in.
struct ShareAuthChecker {

    private static let appGroupId = Bundle.main.infoDictionary?["AppGroupIdentifier"] as? String ?? "group.id.homebase.feed"
    private static let cacheFile = "share_conversation_cache.json"

    static func isAuthenticated() -> Bool {
        guard let containerUrl = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupId) else {
            return false
        }
        let cachePath = containerUrl.appendingPathComponent(cacheFile).path
        return FileManager.default.fileExists(atPath: cachePath)
    }
}
