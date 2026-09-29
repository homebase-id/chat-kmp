plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "kotlin.uuid.ExperimentalUuidApi",
            "kotlin.io.encoding.ExperimentalEncodingApi",
            "kotlin.time.ExperimentalTime",
        )
    }
}

application {
    mainClass.set("id.homebase.agent.MainKt")
}

dependencies {
    implementation(project(":homebase-api"))
    implementation(project(":homebase-chat"))
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
}

// KMP jvm variants resolve to several platform-specific copies of the same jar.
tasks.withType<AbstractCopyTask>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
