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
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

buildscript {
    dependencies {
        classpath(libs.ktlint.rules)
    }
}

subprojects {
    // Enable detekt again when supporting new format
    // https://github.com/detekt/detekt/issues/8981
//    apply(plugin = "dev.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

//    configure<dev.detekt.gradle.extensions.DetektExtension> {
//        buildUponDefaultConfig = true
//        allRules = false
//        config.setFrom("$rootDir/config/detekt.yml")
//    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
//        android.set(true)
//        outputColorName.set("RED")

        filter {
            //exclude("**/generated/**", "**/commonTest/**", "**/androidTest/**")
            exclude { element -> element.file.path.contains("generated/") }
            exclude { element -> element.file.path.contains("/commonTest/") }
            exclude { element -> element.file.path.contains("/androidTest/") }
            //include("**/kotlin/**")
        }
    }
}