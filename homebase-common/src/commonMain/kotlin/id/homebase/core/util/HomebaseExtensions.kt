package id.homebase.core.util

import id.homebase.api.common.OdinId

fun OdinId.buildNotificationUrl(): String {
    return "https://${this.domainName}/owner/notifications"
}