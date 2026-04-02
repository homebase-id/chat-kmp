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
}
