import java.time.LocalDate

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
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.kermit)
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.kotlinx.io.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
}

val gitSha = providers.exec {
    commandLine("git", "-C", rootDir.absolutePath, "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

tasks.processResources {
    val sha = gitSha.get()
    val date = LocalDate.now().toString()
    inputs.property("sha", sha)
    inputs.property("date", date)
    filesMatching("chat-agent-version.properties") { expand("sha" to sha, "date" to date) }
}

// KMP jvm variants resolve to several platform-specific copies of the same jar.
tasks.withType<AbstractCopyTask>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }

// Headless CLI: no UI, media playback, desktop notifications or PDF rendering runs here. The jar list is proven by trimmedTest + the smoke in README.
val unusedJarPatterns = listOf(
    "-desktop-\\d", "^skiko-", "^coil-", "^multiplatform-markdown-renderer", "^reorderable-", "^filekit-", "^vlcj", "^dbus-java",
    "^nucleus\\.", "^kmpnotifier-", "^koin-compose", "^image-editor-ui", "^kotlinx-coroutines-swing", "^jbr-api", "^pdfbox", "^fontbox",
    "^jna-platform", "^markdown-jvm", "^sqlite-jdbc", "^sqlite-driver", "^jdbc-driver", "^sqldelight", "^jna-\\d", "^homebase-common-jvm", "^homebase-notifshared",
)

val chatJarName = "homebase-chat-jvm.jar"

val slimChatJar = tasks.register<Jar>("slimChatJar") {
    archiveFileName.set(chatJarName)
    destinationDirectory.set(layout.buildDirectory.dir("slim"))
    val chat = chatJarName
    from({ configurations.runtimeClasspath.get().filter { it.name == chat }.map { zipTree(it) } }) { exclude("ffmpeg/**") }
}

fun trimmed(files: FileCollection, patterns: List<String>, chat: String): FileCollection {
    val regexes = patterns.map { Regex(it) }
    return files.filter { f -> regexes.none { it.containsMatchIn(f.name) } && f.name != chat }
}

tasks.startScripts {
    classpath = trimmed(files(tasks.jar) + configurations.runtimeClasspath.get(), unusedJarPatterns, chatJarName) + files(slimChatJar)
}

listOf(tasks.installDist, tasks.distTar).forEach { task ->
    task.configure {
        val regexes = unusedJarPatterns.map { Regex(it) }
        val chat = chatJarName
        exclude { it.name.endsWith(".jar") && (regexes.any { r -> r.containsMatchIn(it.name) } || (it.name == chat && !it.file.path.contains("/build/slim/"))) }
    }
}
distributions.main {
    contents {
        from(slimChatJar) { into("lib") }
        from("scripts") { into("scripts") }
        from("deploy") { into("deploy") }
        from("README.md")
    }
}

tasks.distTar {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

tasks.register<Test>("trimmedTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = trimmed(sourceSets.test.get().runtimeClasspath, unusedJarPatterns, chatJarName) + files(slimChatJar)
}
