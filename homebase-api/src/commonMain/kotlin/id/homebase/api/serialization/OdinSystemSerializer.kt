package id.homebase.homebasekmppoc.prototype.lib.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.Uuid

/**
 * Custom camelCase naming strategy to match C# JsonNamingPolicy.CamelCase
 * Converts PascalCase property names to camelCase (first letter lowercase)
 */
object CamelCaseNamingStrategy : JsonNamingStrategy {
    override fun serialNameForJson(descriptor: SerialDescriptor, elementIndex: Int, serialName: String): String {
        return serialName.replaceFirstChar { it.lowercase() }
    }
}

/**
 * Centralizes serialization functions using kotlinx.serialization
 */
object OdinSystemSerializer {
    /**
     * JSON configuration with camelCase naming strategy
     * Matches C# JsonSerializerOptions behavior:
     * - PropertyNameCaseInsensitive = true → ignoreUnknownKeys = true
     * - PropertyNamingPolicy = JsonNamingPolicy.CamelCase → custom CamelCaseNamingStrategy
     * - JsonStringEnumConverter → handled by @Serializable on enum classes (serializes as strings by default)
     * - ByteArrayConverter → built-in (Base64)
     * - GuidConverter/NullableGuidConverter → handled by @Serializable(with = GuidIdSerializer::class)
     *
     * Additional options:
     * - isLenient = false → strict JSON parsing (default)
     * - allowStructuredMapKeys = true → allows complex types as map keys
     * - prettyPrint = false → compact JSON output (minified)
     * - explicitNulls = false → exclude null values from output for compactness
     * - coerceInputValues = false → don't coerce invalid values to defaults (default)
     */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        namingStrategy = CamelCaseNamingStrategy
        isLenient = false
        allowStructuredMapKeys = true
        prettyPrint = false
        explicitNulls = false
        coerceInputValues = false

        serializersModule = SerializersModule {
            contextual(Uuid::class, UuidSerializer)
            // more custom serializers can be registered here as needed...
        }
    }

    /**
     * Serialize a value to JSON string
     */
    inline fun <reified T> serialize(value: T): String {
        return json.encodeToString(value)
    }

    /**
     * Deserialize a JSON string to type T
     */
    inline fun <reified T> deserialize(jsonString: String): T {
        return json.decodeFromString(jsonString)
    }
}
