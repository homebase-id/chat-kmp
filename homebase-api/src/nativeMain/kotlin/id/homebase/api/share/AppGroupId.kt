package id.homebase.api.share

import platform.Foundation.NSBundle

/** Each process reads this from its own Info.plist, so the app and every extension must carry it. */
internal val APP_GROUP_ID: String = NSBundle.mainBundle.infoDictionary
    ?.get("AppGroupIdentifier") as? String ?: "group.id.homebase.feed"
