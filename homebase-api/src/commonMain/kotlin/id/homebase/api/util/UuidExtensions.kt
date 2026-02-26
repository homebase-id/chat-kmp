package id.homebase.api.util

fun compareStringUuId(str1: String?, str2: String?): Boolean {
    if (str1 == null || str2 == null) return false
    return str1.lowercase().replace("-", "") == str2.lowercase().replace("-", "")
}
