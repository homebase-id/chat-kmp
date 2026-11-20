package id.homebase.api.image

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * An iPhone's own HDR gain map (`HDRGainMap`, before ISO 21496-1) in ISO terms, so the shared Ultra HDR writer can
 * carry it. Apple's rendition is HDR = SDR × (1 + (headroom − 1) × g), where g is the map's value through the sRGB
 * curve; ISO's is HDR = SDR × 2^(log2(headroom) × g'). [isoValues] re-encodes g as g', which is exact.
 */
internal object AppleHdrGainmap {
    const val NAMESPACE = "http://ns.apple.com/HDRGainMap/1.0/"

    /** The headroom from the MakerNote's tags 33 and 48, by Apple's published formula, or null when it's not HDR. */
    fun headroom(maker33: Double, maker48: Double): Double? {
        val stops = if (maker33 < 1.0) {
            if (maker48 <= 0.01) -20.0 * maker48 + 1.8 else -0.101 * maker48 + 1.601
        } else {
            if (maker48 <= 0.01) -70.0 * maker48 + 3.0 else -0.303 * maker48 + 2.303
        }
        return 2.0.pow(max(stops, 0.0)).takeIf { it > 1.0 }
    }

    /** `HDRGainMapHeadroom`, which iOS 18 writes beside the map, or null. */
    fun headroomFromXmp(xml: String): Double? =
        xmpValues(xml, NAMESPACE, "HDRGainMapHeadroom").firstOrNull()?.toDoubleOrNull()?.takeIf { it > 1.0 }

    fun metadata(headroom: Double): GainmapMetadata {
        val stops = log2(headroom)
        return GainmapMetadata(
            gainMapMin = listOf(0.0), gainMapMax = listOf(stops), gamma = listOf(1.0),
            offsetSdr = listOf(0.0), offsetHdr = listOf(0.0), hdrCapacityMin = 0.0, hdrCapacityMax = stops,
        )
    }

    /** Each 8-bit value of Apple's map, as the value the ISO map from [metadata] needs for the same boost. */
    fun isoValues(headroom: Double): ByteArray = ByteArray(256) { v ->
        val linear = srgbToLinear(v / 255.0)
        (log2(1 + (headroom - 1) * linear) / log2(headroom) * 255).roundToInt().coerceIn(0, 255).toByte()
    }

    private fun srgbToLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

/**
 * ISO 21496-1 metadata as ImageIO hands it over with an HEIC's gain map: Apple's `HDRToneMap` XMP, headrooms and
 * boosts as log2, like [GainmapMetadata].
 */
internal object AppleToneMapXmp {
    const val NAMESPACE = "http://ns.apple.com/HDRToneMap/1.0/"

    /** Null without the values, or when the base image is the HDR one. */
    fun decode(xml: String): GainmapMetadata? {
        if (NAMESPACE !in xml) return null
        fun all(name: String) = xmpValues(xml, NAMESPACE, name).map { it.toDoubleOrNull() }
        fun channels(name: String): List<Double>? = all(name).takeIf { it.size == 1 || it.size == 3 }?.map { it ?: return null }
        val base = all("BaseHeadroom").singleOrNull() ?: return null
        val alternate = all("AlternateHeadroom").singleOrNull() ?: return null
        if (alternate <= base) return null
        return GainmapMetadata(
            gainMapMin = channels("GainMapMin") ?: return null,
            gainMapMax = channels("GainMapMax") ?: return null,
            gamma = channels("Gamma") ?: return null,
            offsetSdr = channels("BaseOffset") ?: return null,
            offsetHdr = channels("AlternateOffset") ?: return null,
            hdrCapacityMin = base,
            hdrCapacityMax = alternate,
            useBaseColorSpace = xmpValues(xml, NAMESPACE, "BaseColorIsWorkingColor").firstOrNull()?.equals("False", ignoreCase = true) != true,
        )
    }
}

/**
 * Every value of the property [name] in [namespace], under whatever prefix the XMP binds it to, written as an
 * attribute or as a simple element.
 */
internal fun xmpValues(xml: String, namespace: String, name: String): List<String> {
    val prefixes = Regex("""xmlns:([\w.-]+)\s*=\s*["']${Regex.escape(namespace)}["']""").findAll(xml).map { it.groupValues[1] }.toSet()
    return prefixes.flatMap { prefix ->
        Regex("""(?<![/\w.-])${Regex.escape(prefix)}:$name\s*(=\s*["']([^"']*)["']|>([^<]*)<)""").findAll(xml)
            .map { (it.groupValues[2].ifEmpty { it.groupValues[3] }).trim() }
    }
}
