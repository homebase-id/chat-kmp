package id.homebase.chat.services.calling

import id.homebase.api.client.CryptoHelper
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.liverelay.LiveRelayProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fake odin-core Live Relay behind the real [LiveRelayProvider] (Ktor MockEngine): the request body is decrypted and
 * parsed like the server would, only the LAST blob per sender is kept for each recipient, and it is flushed to a
 * device when its socket connects. Devices that are online get every blob immediately.
 */
class LastValueRelay {
    private val secret = ByteArray(16)

    class Post(val sender: String, val recipients: List<String>, val channelKey: String, val signal: CallSignal, val body: JsonObject)

    class Device(val identity: String) {
        val bus = EventBus()
        @Volatile var online = false
    }

    private class Retained(val channelKey: String, val blob: String)

    val posts = CopyOnWriteArrayList<Post>()
    private val devices = CopyOnWriteArrayList<Device>()
    private val retained = ConcurrentHashMap<Pair<String, String>, Retained>()

    /** How long each ICE post takes to come back, like a real relay round trip. */
    @Volatile var iceSendDelayMs = 0L

    fun device(identity: String, online: Boolean): Device =
        Device(identity).also { devices += it; if (online) it.online = true }

    suspend fun relayFor(sender: String): LiveRelaySend {
        val creds = CredentialsManager()
        creds.setActiveCredentials(ApiCredentials.create(OdinId(sender), "token", SecureByteArray(secret.copyOf())))
        val engine = MockEngine { request ->
            val plain = CryptoHelper.decryptContentAsString(request.body.toByteArray().decodeToString(), secret)
            val signal = accept(sender, Json.parseToJsonElement(plain).jsonObject)
            if (signal is CallSignal.Ice) delay(iceSendDelayMs)
            respond("", HttpStatusCode.NoContent)
        }
        val provider = LiveRelayProvider(HttpClient(engine), creds)
        return { channelKey, recipients, blob, push -> provider.relay(channelKey, recipients, blob, push, wakeCause = null) }
    }

    private fun accept(sender: String, body: JsonObject): CallSignal {
        val channelKey = body.getValue("channelKey").jsonPrimitive.content
        val blob = body.getValue("blob").jsonPrimitive.content
        val recipients = (body.getValue("recipients") as JsonArray).map { it.jsonPrimitive.content }
        val signal = checkNotNull(CallSignalCodec.decode(blob))
        posts += Post(sender, recipients, channelKey, signal, body)
        // The real relay is peer-to-peer over connections; an identity is not connected to itself, so a self-addressed post is accepted (204) and dropped.
        for (recipient in recipients.filter { it != sender }) {
            retained[recipient to sender] = Retained(channelKey, blob)
            devices.filter { it.identity == recipient && it.online }.forEach { deliver(it, sender, channelKey, blob) }
        }
        return signal
    }

    fun connect(device: Device) {
        device.online = true
        retained.filterKeys { it.first == device.identity }.forEach { (key, last) -> deliver(device, key.second, last.channelKey, last.blob) }
    }

    fun lastRetained(recipient: String, sender: String): CallSignal? =
        retained[recipient to sender]?.let { CallSignalCodec.decode(it.blob) }

    fun replay(device: Device, sender: String, signal: CallSignal, channelKey: String) =
        deliver(device, sender, channelKey, CallSignalCodec.encode(signal))

    private fun deliver(device: Device, sender: String, channelKey: String, blob: String) {
        device.bus.tryEmit(BackendEvent.LiveRelayReceived(OdinId(sender), channelKey, blob, System.currentTimeMillis()))
    }

    fun signalsFrom(sender: String): List<CallSignal> = posts.filter { it.sender == sender }.map { it.signal }
}
