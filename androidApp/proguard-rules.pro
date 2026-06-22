# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep line numbers in release stack traces. Obfuscation stays ON — this only
# preserves the line-number table so Crashlytics / Play Console (which have the
# uploaded mapping.txt) and `retrace` resolve crashes to the exact original
# file + line instead of showing "Unknown Source".
-keepattributes SourceFile,LineNumberTable

# Replace the original .kt file name with a constant ("SourceFile") in the
# bytecode — keeps the line numbers above while still hiding source file names.
# The mapping file maps everything back during retrace/Crashlytics.
-renamesourcefileattribute SourceFile

# Filekit
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# AndroidX WorkManager / Room
-keep class androidx.work.impl.** { *; }
-keep class androidx.room.** { *; }

# Homebase API
-keep class id.homebase.api.** { *; }

# Play Core
-keep class com.google.android.play.core.** { *; }
-dontwarn com.google.android.play.core.**

# Global coroutine exception handler — instantiated by fully-qualified name via
# ServiceLoader (META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler),
# so R8 must neither rename nor strip it.
-keep class id.homebase.feed.GlobalCoroutineExceptionHandler { *; }