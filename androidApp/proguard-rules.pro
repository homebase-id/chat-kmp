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

# Disable identifier renaming only. R8 shrinking + optimization stay on (the AAB
# size wins are theirs, not renaming's). The app is open-source, so renamed
# identifiers protect nothing public; leaving names intact makes raw traces
# (logcat / homebase.log / crash-recovery screen / screenshots) directly readable
# with no retrace / per-build mapping.txt.
-dontobfuscate

# Keep line numbers in release stack traces so Crashlytics / Play Console and
# `retrace` resolve crashes to the exact original file + line instead of
# "Unknown Source".
-keepattributes SourceFile,LineNumberTable

# Moot with -dontobfuscate (no renaming happens); kept to minimise the diff.
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