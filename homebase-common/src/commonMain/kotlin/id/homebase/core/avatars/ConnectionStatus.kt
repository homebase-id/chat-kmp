package id.homebase.core.avatars

/**
 * App-level connection status (WebSocket + handshake).
 * Distinct from the social-graph [id.homebase.api.client.connections.ConnectionStatus]
 * which describes whether two users are connected to each other.
 *
 * Use [id.homebase.core.auth.AuthConnectionCoordinator.isOnline] as the canonical
 * boolean online check.
 */
enum class AppConnectionStatus { Disconnected, Connecting, Connected }
