# GLOBAL Optimization settings
-keepattributes
-dontnote **
-dontoptimize
-printmapping mapping.txt

# Provide for stack traces
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
-keepattributes Exceptions,Signature,InnerClasses,RuntimeVisibleAnnotations,AnnotationDefault,EnclosingMethod,*Annotation*

# With R8 full mode generic signatures are stripped for classes that are not
# kept. Suspend functions are wrapped in continuations where the type argument
# is used.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

-keepnames class kotlinx.** { *; }
-keepclassmembers class kotlinx.** {
    volatile <fields>;
}

# Filekit
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# Ktor
-dontwarn io.netty.**
-dontwarn io.ktor.**
-dontwarn com.typesafe.**

# Homebase
-keep class id.homebase.** { *; }

# Compose native tray
-keep class com.sun.jna.** { *; }
-keep class com.kdroid.composetray.** { *; }

# Koin
-keep class org.koin.** { *; }

# ========================================
# SQLite JDBC with encryption support (io.github.willena:sqlite-jdbc)
# ========================================

# Keep ALL SQLite classes without obfuscation
-keep,allowoptimization class org.sqlite.** { *; }
-keepnames class org.sqlite.** { *; }

# Specifically preserve core packages
-keep class org.sqlite.core.** { *; }
-keep class org.sqlite.jdbc3.** { *; }
-keep class org.sqlite.jdbc4.** { *; }
-keep class org.sqlite.util.** { *; }
-keep class org.sqlite.date.** { *; }

# Critical: Multi-cipher support (SQLCipher, ChaCha20, etc.)
-keep class org.sqlite.mc.** { *; }
-keepclassmembers class org.sqlite.mc.** { *; }

# Keep SQLite configuration - used via reflection for URL parameters
-keep class org.sqlite.SQLiteConfig { *; }
-keep class org.sqlite.SQLiteConfig$* { *; }
-keepclassmembers class org.sqlite.SQLiteConfig {
    public <methods>;
    public <fields>;
}

# Keep multi-cipher configuration - critical for encryption
-keep class org.sqlite.mc.SQLiteMCConfig { *; }
-keep class org.sqlite.mc.SQLiteMCConfig$* { *; }
-keepclassmembers class org.sqlite.mc.SQLiteMCConfig {
    *;
}

# Keep JDBC driver class for service loader
-keep class org.sqlite.JDBC { *; }
-keepclassmembers class org.sqlite.JDBC {
    *;
}

# Keep all native methods (required for JNI native library loading)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep SQLite native database class
-keep class org.sqlite.core.NativeDB { *; }
-keepclassmembers class org.sqlite.core.NativeDB {
    *;
}

# Keep DB class that handles connections
-keep class org.sqlite.core.DB { *; }
-keepclassmembers class org.sqlite.core.DB {
    *;
}

# Keep all enum values (SQLite uses enums for configuration)
-keepclassmembers enum org.sqlite.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    *;
}

# Keep all Pragmas enum used for configuration
-keepclassmembers enum org.sqlite.SQLiteConfig$Pragma {
    *;
}

# Prevent removal of methods accessed via reflection
-keepclassmembers class org.sqlite.** {
    public <methods>;
    public <fields>;
}

# Keep connection classes
-keep class org.sqlite.SQLiteConnection { *; }
-keep class org.sqlite.jdbc3.JDBC3Connection { *; }
-keep class org.sqlite.jdbc4.JDBC4Connection { *; }

# Don't warn about missing optional dependencies
-dontwarn org.sqlite.**

# ========================================
# End SQLite rules
# ========================================

# Kotlinx Serialization
# Keep `Companion` object field of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
 -keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
 }

# Keep names for named companion object from obfuscation
# Names of a class and of a field are important in lookup of named companion in runtime
-if @kotlinx.serialization.internal.NamedCompanion class *
-keepclassmembers class * {
    static <1> *;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Don't print notes about potential mistakes or omissions in the configuration for kotlinx-serialization classes
# See also https://github.com/Kotlin/kotlinx.serialization/issues/1900
-dontnote kotlinx.serialization.**
# Serialization core uses `java.lang.ClassValue` for caching inside these specified classes.
# If there is no `java.lang.ClassValue` (for example, in Android), then R8/ProGuard will print a warning.
# However, since in this case they will not be used, we can disable these warnings
-dontwarn kotlinx.serialization.internal.ClassValueReferences

# disable optimisation for descriptor field because in some versions of ProGuard, optimization generates incorrect bytecode that causes a verification error
# see https://github.com/Kotlin/kotlinx.serialization/issues/2719
-keepclassmembers public class **$$serializer {
    private ** descriptor;
}

# Ktor
-keep class io.netty.** {*; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.network.sockets.**

# Misc
-keep class dev.whyoleg.cryptography.** { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-keep class kotlin.text.RegexOption { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.compose.ui.** { *; }
