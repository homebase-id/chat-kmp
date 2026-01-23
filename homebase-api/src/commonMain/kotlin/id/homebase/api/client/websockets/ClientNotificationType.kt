package id.homebase.homebasekmppoc.prototype.lib.websockets

import kotlinx.serialization.Serializable

@Serializable
enum class ClientNotificationType {
    deviceHandshakeSuccess,
    pong,
    fileAdded,
    fileDeleted,
    fileModified,
    connectionRequestReceived,
    deviceConnected,
    deviceDisconnected,
    connectionRequestAccepted,
    inboxItemReceived,
    newFollower,
    statisticsChanged,
    reactionContentAdded,
    reactionContentDeleted,
    allReactionsByFileDeleted,
    appNotificationAdded,
    introductionsReceived,
    introductionAccepted,
    connectionFinalized,
    unused,
    error,
    authenticationError
}
