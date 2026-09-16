package id.homebase.chat.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.russhwolf.settings.Settings
import id.homebase.core.settings.UserPreferences
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

// The composer fields read the Enter chord through rememberEnterSendsMessage() -> koinInject.
@Composable
internal fun WithComposerPreferences(
    enterSendsMessage: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Isolated, not KoinApplication { }: that one starts and stops the global context, which the
    // other tests sharing this module's test JVM are already using.
    val app = remember(enterSendsMessage) {
        val prefs = UserPreferences(InMemorySettings())
        prefs.enterSendsMessage = enterSendsMessage
        koinApplication { modules(module { single { prefs } }) }
    }
    KoinIsolatedContext(app) { content() }
}

private class InMemorySettings : Settings {
    private val backing: MutableMap<String, Any> = mutableMapOf()
    override val keys: Set<String> get() = backing.keys.toSet()
    override val size: Int get() = backing.size
    override fun clear() = backing.clear()
    override fun remove(key: String) { backing.remove(key) }
    override fun hasKey(key: String): Boolean = backing.containsKey(key)
    override fun putInt(key: String, value: Int) { backing[key] = value }
    override fun getInt(key: String, defaultValue: Int): Int = (backing[key] as? Int) ?: defaultValue
    override fun getIntOrNull(key: String): Int? = backing[key] as? Int
    override fun putLong(key: String, value: Long) { backing[key] = value }
    override fun getLong(key: String, defaultValue: Long): Long = (backing[key] as? Long) ?: defaultValue
    override fun getLongOrNull(key: String): Long? = backing[key] as? Long
    override fun putString(key: String, value: String) { backing[key] = value }
    override fun getString(key: String, defaultValue: String): String = (backing[key] as? String) ?: defaultValue
    override fun getStringOrNull(key: String): String? = backing[key] as? String
    override fun putFloat(key: String, value: Float) { backing[key] = value }
    override fun getFloat(key: String, defaultValue: Float): Float = (backing[key] as? Float) ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = backing[key] as? Float
    override fun putDouble(key: String, value: Double) { backing[key] = value }
    override fun getDouble(key: String, defaultValue: Double): Double = (backing[key] as? Double) ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = backing[key] as? Double
    override fun putBoolean(key: String, value: Boolean) { backing[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = (backing[key] as? Boolean) ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = backing[key] as? Boolean
}
