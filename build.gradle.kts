plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
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

subprojects {
    // Gradle's default SHORT exception format prints only "<ExceptionClass> at File.kt:<line>",
    // and for a coroutine test it attributes that line to the enclosing `runTest {` rather than
    // to the failing assertion — so a CI-only failure arrives with neither the assertion message
    // nor a usable line. That is not enough to triage a flake from a log alone. Print the full
    // trace and message for failing tests.
    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
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
