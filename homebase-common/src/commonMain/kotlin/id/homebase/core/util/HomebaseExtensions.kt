package id.homebase.core.util

import id.homebase.api.common.OdinId

fun OdinId.buildNotificationUrl(): String {
    return "https://${this.domainName}/owner/connections"
}

fun OdinId.buildConnectToIdentityUrl(connectId: OdinId): String {
    return "https://${this.domainName}/owner/connections/${connectId.domainName}/connect"
}

fun OdinId.buildBlockUrl(targetId: OdinId): String {
    return "https://${this.domainName}/owner/connections/${targetId.domainName}/block"
}
