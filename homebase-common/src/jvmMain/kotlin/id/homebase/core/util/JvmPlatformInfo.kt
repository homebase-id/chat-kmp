package id.homebase.core.util

class JvmPlatformInfo: PlatformInfo {
    override val versionName: String
        get() = try {
            val properties = java.util.Properties()
            this::class.java.classLoader?.getResourceAsStream("version.properties")?.use {
                properties.load(it)}
            properties.getProperty("version.name", "Unknown")
        } catch (e: Exception) {
            "Unknown"
        }

    override val versionCode: Int
        get() = try {
            val properties = java.util.Properties()
            this::class.java.classLoader?.getResourceAsStream("version.properties")?.use {
                properties.load(it)
            }
            properties.getProperty("version.code", "0").toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }

}