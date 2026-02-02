import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

//    @OptIn(ExperimentalWasmDsl::class)
//    wasmJs {
//        binaries.executable()
//        browser {
//            commonWebpackConfig {
//                outputFileName = "homebase-app.js"
//                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
//                    static(project.projectDir.path)
//                }
//            }
//        }
//    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":homebase-common"))
            implementation(project(":homebase-chat"))
            implementation(project(":homebase-core"))

            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.resources)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.navigation.compose)

        }
    }
}

