# GLOBAL Optimization settings
-keepattributes
-dontnote **
-dontoptimize
-printmapping mapping.txt

# Filekit
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# Ktor
-dontwarn io.netty.**
-dontwarn io.ktor.**
-dontwarn com.typesafe.**

# Homebase API
-keep class id.homebase.api.** { *; }