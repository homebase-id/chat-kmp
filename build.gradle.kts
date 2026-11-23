plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidNativeLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.androidLint) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.firebaseCrashlytics) apply false
    alias(libs.plugins.buildConfigPlugin) apply false
}

// Robolectric would otherwise download these at test time into ~/.m2, outside Gradle's cache; one per SDK level the tests run on (34, 36).
// One configuration each: same module with two versions would be conflict-resolved down to one jar.
val robolectricAndroidAll = listOf("14-robolectric-10818077-i7", "16-robolectric-13921718-i7").map { version ->
    configurations.create("robolectricAndroidAll${version.substringBefore('-')}").also {
        dependencies.add(it.name, "org.robolectric:android-all-instrumented:$version")
    }
}
val robolectricJarsDir = layout.buildDirectory.dir("robolectric-android-all")
val syncRobolectricJars by tasks.registering(Sync::class) {
    robolectricAndroidAll.forEach { from(it) }
    into(robolectricJarsDir)
}

subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("homebase.secureStorage", "file")
    }

    tasks.withType<Test>().matching { it.name == "testAndroidHostTest" || it.name.endsWith("UnitTest") }.configureEach {
        dependsOn(syncRobolectricJars)
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", robolectricJarsDir.get().asFile.path)
    }

    configurations.all {
        resolutionStrategy {
            // Force encrypted sqlite-jdbc version everywhere
            force("io.github.willena:sqlite-jdbc:3.51.2.0")

            // Exclude standard sqlite-jdbc in favor of encrypted version
            eachDependency {
                if (requested.group == "org.xerial" && requested.name == "sqlite-jdbc") {
                    useTarget("io.github.willena:sqlite-jdbc:3.51.2.0")
                    because("Using encrypted SQLite JDBC driver")
                }
            }
        }
    }
}
