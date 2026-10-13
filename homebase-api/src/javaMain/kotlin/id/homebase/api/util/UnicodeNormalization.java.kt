package id.homebase.api.util

import java.text.Normalizer

actual fun String.toNfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)
