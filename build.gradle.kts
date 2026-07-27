import org.gradle.api.tasks.testing.logging.TestExceptionFormat

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

    // Without this a CI failure prints only `java.lang.AssertionError at Foo.kt:171`
    // — no assertion message, no stack. For a coroutine test that line is the
    // `runTest` lambda, so it doesn't even say which assertion blew, and an
    // intermittent failure is undiagnosable from the logs alone.
    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
