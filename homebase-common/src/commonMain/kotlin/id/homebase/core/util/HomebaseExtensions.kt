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

/** The owner console's Security → Email tab: the mail DNS records, and the repair button. */
fun OdinId.buildOwnerEmailSettingsUrl(): String {
    return "https://${this.domainName}/owner/security/email"
}

/** The owner console's Security → DNS tab, where DNSSEC is set up. */
fun OdinId.buildOwnerDnsSettingsUrl(): String {
    return "https://${this.domainName}/owner/security/dns"
}
