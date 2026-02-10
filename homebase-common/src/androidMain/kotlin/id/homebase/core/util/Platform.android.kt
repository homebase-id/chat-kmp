package id.homebase.core.util

import android.os.Build

actual object Platform {
    actual val osName: String = "Android"
    actual val osVersion: String = "${Build.VERSION.SDK_INT}"
}
