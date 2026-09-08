@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.core.notifications

import kotlin.js.Promise
import kotlinx.coroutines.await

/* ------------------------------------------------------------------------------------------
 * JS interop with the `globalThis.__odinPush` bridge defined in webApp's `odin-push.js`.
 *
 * Results cross as ";"-joined strings (same idiom as FFmpegBridge) with the endpoint LAST, so a
 * ";" inside a push endpoint survives the split. Failures resolve rather than reject: awaiting a
 * rejected JS promise on coroutines 1.10.2 loses the DOMException.name, and NotAllowedError vs
 * AbortError is exactly what has to be told apart.
 * ------------------------------------------------------------------------------------------ */

private fun pushCapability(): String =
    js("globalThis.__odinPush ? globalThis.__odinPush.capability() : 'unsupported'")

private fun pushRequestPermission(): Promise<JsString> =
    js("globalThis.__odinPush.requestPermission()")

private fun pushSubscribe(vapidKey: String): Promise<JsString> =
    js("globalThis.__odinPush.subscribe(vapidKey)")

private fun pushCurrentSubscription(): Promise<JsString> =
    js("globalThis.__odinPush.currentSubscription()")

private fun pushUnsubscribe(): Promise<JsAny?> = js("globalThis.__odinPush.unsubscribe()")

private fun pushOnClick(cb: (String) -> Unit): Unit = js("globalThis.__odinPush.onClick(cb)")

private fun pushShowLocalNotification(title: String, body: String): Promise<JsString> =
    js("globalThis.__odinPush.showLocalNotification(title, body)")

private fun hasPushBridge(): Boolean = js("typeof globalThis.__odinPush !== 'undefined'")

actual fun webPushBridge(): WebPushBridge? = if (hasPushBridge()) BrowserPushBridge else null

private object BrowserPushBridge : WebPushBridge {
    override fun capability(): WebPushCapability = parseCapability(pushCapability())

    override suspend fun requestPermission(): WebPushCapability =
        parseCapability(pushRequestPermission().await<JsString>().toString())

    override suspend fun subscribe(vapidKey: String): WebPushSubscribeResult {
        val raw = pushSubscribe(vapidKey).await<JsString>().toString()
        val keys = parseSubscription(raw)
        return if (keys != null) WebPushSubscribeResult.Success(keys)
        else WebPushSubscribeResult.Failure(raw.split(";", limit = 2).getOrNull(1) ?: "Error")
    }

    override suspend fun currentSubscription(): WebPushSubscriptionKeys? =
        parseSubscription(pushCurrentSubscription().await<JsString>().toString())

    override suspend fun unsubscribe() {
        pushUnsubscribe().await<JsAny?>()
    }

    override fun onNotificationClick(handler: (String) -> Unit) {
        pushOnClick(handler)
    }

    override suspend fun showLocalNotification(title: String, body: String): String? {
        val raw = pushShowLocalNotification(title, body).await<JsString>().toString()
        return if (raw == "ok") null else raw.split(";", limit = 2).getOrNull(1) ?: "Error"
    }
}

private fun parseCapability(raw: String): WebPushCapability = when (raw) {
    "granted" -> WebPushCapability.GRANTED
    "denied" -> WebPushCapability.DENIED
    "default" -> WebPushCapability.DEFAULT
    "needs-install" -> WebPushCapability.NEEDS_INSTALL
    else -> WebPushCapability.UNSUPPORTED
}

private fun parseSubscription(raw: String): WebPushSubscriptionKeys? {
    val parts = raw.split(";", limit = 5)
    if (parts.size < 5 || parts[0] != "ok") return null
    return WebPushSubscriptionKeys(
        endpoint = parts[4],
        auth = parts[1],
        p256dh = parts[2],
        expirationTime = parts[3].toLongOrNull(),
    )
}
