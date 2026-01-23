package id.homebase.homebasekmppoc.lib.youauth

/**
 * Permission types for circle-level access. These define what access is granted within a specific
 * circle.
 */
enum class CirclePermissionType(val value: Int) {
    None(0),
    ReadConnections(10),
    IntroduceMe(808);

    companion object {
        fun fromValue(value: Int): CirclePermissionType? = entries.find { it.value == value }
    }
}

/**
 * Permission types for app access within circles. These define what an app can do within user's
 * circles.
 */
enum class AppCirclePermissionType(val value: Int) {
    None(0),
    ReadConnections(10),
    ReadCircleMembers(50),
    ReadWhoIFollow(80),
    ReadMyFollowers(130);

    companion object {
        fun fromValue(value: Int): AppCirclePermissionType? = entries.find { it.value == value }
    }
}

/** Permission types for app-level access. These define general permissions an app can request. */
enum class AppPermissionType(val value: Int) {
    None(0),
    ReadConnections(10),
    ReadConnectionRequests(30),
    ReadCircleMembers(50),
    ReadWhoIFollow(80),
    ReadMyFollowers(130),
    ManageFeed(150),
    SendDataToOtherIdentitiesOnMyBehalf(210),
    ReceiveDataFromOtherIdentitiesOnMyBehalf(305),
    SendPushNotifications(405),
    PublishStaticContent(505),
    SendIntroductions(909);

    companion object {
        fun fromValue(value: Int): AppPermissionType? = entries.find { it.value == value }

        /** Convert a list of permission types to their integer values. */
        fun toValues(permissions: List<AppPermissionType>): List<Int> = permissions.map { it.value }
    }
}
