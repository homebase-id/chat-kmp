import java.io.ByteArrayOutputStream
import org.apache.tools.ant.filters.ReplaceTokens
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// wasm-opt with Kotlin's default pass list (--type-ssa, -O3 four times over, --type-merging, -Oz)
// peaked at 10.3 GB RSS on this app (measured 2026-08-31). Next to the 8 GB Gradle and Kotlin
// daemons, that OOM-killed every CI image build on GitHub's 16 GB runners - surfacing as
// "The operation was canceled" mid compileProductionExecutableKotlinWasmJsOptimize. The lighter
// pass list below measured 4.4 GB peak and ran 3x faster on the same input, costing ~9% output
// size (22.2 MB -> 24.2 MB). Feature and safety flags are identical to Kotlin's defaults; only
// the optimization passes changed. Revisit if binaryen or the runners change shape.
tasks.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenExec>().configureEach {
    binaryenArgs = mutableListOf(
        "--enable-gc",
        "--enable-reference-types",
        "--enable-exception-handling",
        "--enable-bulk-memory",
        "--enable-nontrapping-float-to-int",
        "--closed-world",
        "--no-inline=kotlin.wasm.internal.throwValue",
        "--no-inline=kotlin.wasm.internal.getKotlinException",
        "--no-inline=kotlin.wasm.internal.jsToKotlinStringAdapter",
        "--inline-functions-with-loops",
        "--traps-never-happen",
        "--fast-math",
        "-O2",
        "--gufa",
        "-Oz",
    )
    if (name == productionOptimizeTask) keepWasmCrashSymbols()
}

// A production .wasm has no function names, so a trap reports only `wasm-function[N]:0xOFF`. The
// optimizer run that makes the shipped binary also writes names (-g) and a source map, and the
// names are then cut from the shipped copy, which changes no function index or code offset. They
// must come from that same run: two wasm-opt runs on one input are not byte-identical. Adds
// ~0.5 GB to wasm-opt's peak (5.4 GB measured 2026-10-08). See WASM_CRASH_SYMBOLS.md.
val productionOptimizeTask: String get() = "compileProductionExecutableKotlinWasmJsOptimize"
val wasmSymbolsWorkDir: File get() = layout.buildDirectory.dir("wasm-symbols").get().asFile

fun org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenExec.keepWasmCrashSymbols() {
    val linkedWasm = inputFileProperty
    val workDir = wasmSymbolsWorkDir
    val binaryenInputMap = File(workDir, "binaryen-input.wasm.map")
    val namedWasm = File(workDir, "chat-kmp-webApp.wasm")
    val namedMap = File(workDir, "chat-kmp-webApp.wasm.map")
    val optimizedWasm = outputFileProperty

    binaryenArgs = (binaryenArgs + listOf(
        "-g",
        "--input-source-map", binaryenInputMap.path,
        "--output-source-map", namedMap.path,
    )).toMutableList()
    outputs.dir(workDir)

    doFirst {
        // binaryen asserts on the null sourcesContent entries Kotlin writes.
        workDir.mkdirs()
        val kotlinMap = File(linkedWasm.get().asFile.path + ".map")
        @Suppress("UNCHECKED_CAST")
        val map = groovy.json.JsonSlurper().parse(kotlinMap) as MutableMap<String, Any?>
        map.keys.retainAll(listOf("version", "sources", "names", "mappings"))
        binaryenInputMap.writeText(groovy.json.JsonOutput.toJson(map))
    }
    doLast {
        fun leb(bytes: ByteArray, start: Int): Pair<Int, Int> {
            var value = 0
            var shift = 0
            var at = start
            while (true) {
                val b = bytes[at++].toInt() and 0xff
                value = value or ((b and 0x7f) shl shift)
                if (b < 0x80) return value to at
                shift += 7
            }
        }

        val shippedWasm = optimizedWasm.get().asFile
        val bytes = shippedWasm.readBytes()
        shippedWasm.copyTo(namedWasm, overwrite = true)
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 8)
        var at = 8
        while (at < bytes.size) {
            val (size, payload) = leb(bytes, at + 1)
            val end = payload + size
            val isNameSection = bytes[at].toInt() == 0 && leb(bytes, payload).let { (len, nameAt) ->
                String(bytes, nameAt, len, Charsets.UTF_8) == "name"
            }
            if (!isNameSection) out.write(bytes, at, end - at)
            at = end
        }
        shippedWasm.writeBytes(out.toByteArray())
    }
}

// Files the symbols under the content-hashed name webpack gave the shipped .wasm, which is the name
// a production stack trace shows. dist/wasmJs/symbols is a sibling of the deployed directory.
val collectWasmCrashSymbols by tasks.registering {
    val workDir = wasmSymbolsWorkDir
    val shippedWasm = tasks.named<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenExec>(
        productionOptimizeTask
    ).flatMap { it.outputFileProperty }
    val distDir = layout.buildDirectory.dir("dist/wasmJs/productionExecutable").get().asFile
    val symbolsDir = layout.buildDirectory.dir("dist/wasmJs/symbols").get().asFile
    dependsOn("wasmJsBrowserDistribution")
    inputs.file(shippedWasm)
    inputs.dir(workDir)
    outputs.dir(symbolsDir)

    doLast {
        val shipped = shippedWasm.get().asFile.readBytes()
        val deployed = distDir.listFiles().orEmpty().filter {
            it.extension == "wasm" && it.length() == shipped.size.toLong() &&
                it.readBytes().contentEquals(shipped)
        }
        val hash = deployed.singleOrNull()?.nameWithoutExtension
            ?: error("Expected exactly one copy of the optimized .wasm in $distDir, found ${deployed.size}")
        symbolsDir.deleteRecursively()
        symbolsDir.mkdirs()
        File(workDir, "chat-kmp-webApp.wasm").copyTo(File(symbolsDir, "$hash.wasm"))
        File(workDir, "chat-kmp-webApp.wasm.map").copyTo(File(symbolsDir, "$hash.wasm.map"))
    }
}

tasks.matching { it.name == "wasmJsBrowserDistribution" }.configureEach {
    finalizedBy(collectWasmCrashSymbols)
}

// Sub-path mount support. Pass -PpublicPath=/apps/chat-wasm/ to host the bundle under a sub-path
// (odin-core's CI does this); defaults to "/" for standalone runs. The normalized value flows to:
//   - webpack `output.publicPath` (via the generated webpack.config.d/00-publicPath.js)
//   - the <base href> injected into index.html (via processResources token substitution)
//   - the dev-server historyApiFallback rewrite (devserver.js reads globalThis.WEBAPP_PUBLIC_PATH)
//   - the YouAuth redirect URI at runtime (RedirectConfig.web.kt reads document.baseURI)
val publicPath: String = (providers.gradleProperty("publicPath").orNull ?: "/").let { raw ->
    val withLeading = if (raw.startsWith("/")) raw else "/$raw"
    if (withLeading.endsWith("/")) withLeading else "$withLeading/"
}

val generateWebpackPublicPathConfig by tasks.registering {
    val outFile = layout.projectDirectory.file("webpack.config.d/00-publicPath.js").asFile
    inputs.property("publicPath", publicPath)
    outputs.file(outFile)
    doLast {
        outFile.parentFile.mkdirs()
        outFile.writeText(
            """
            // AUTO-GENERATED by webApp/build.gradle.kts from -PpublicPath. Do not edit.
            // The alphabetic 00- prefix ensures this file is loaded before sibling config.d files.
            globalThis.WEBAPP_PUBLIC_PATH = '$publicPath';
            config.output = Object.assign({}, config.output, { publicPath: '$publicPath' });
            """.trimIndent() + "\n"
        )
    }
}

// HtmlWebpackPlugin owns index.html so it can substitute the content-hashed bundle filename into
// the bootstrap script. We pull index.html OUT of the resource pipeline (so it's not also copied
// into processedResources -> dist) and instead pre-substitute @PUBLIC_PATH@ into a template under
// build/generated, which HtmlWebpackPlugin reads as its template input.
val htmlTemplateDir = layout.buildDirectory.dir("generated/html-template")
val generateHtmlTemplate by tasks.registering(Copy::class) {
    from(layout.projectDirectory.file("src/wasmJsMain/resources/index.html"))
    into(htmlTemplateDir)
    inputs.property("publicPath", publicPath)
    filter<ReplaceTokens>("tokens" to mapOf("PUBLIC_PATH" to publicPath))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        binaries.executable()
        browser {
            commonWebpackConfig {
                outputFileName = "homebase-app.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static(project.projectDir.path)
                }
            }
        }
    }

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
            implementation(libs.koin.core)

        }
        wasmJsMain.dependencies {
            // Used by webpack.config.d/html-plugin.js to inject the (content-hashed) bundle
            // filename into index.html. Webpack-only — never reaches the wasm output.
            implementation(devNpm("html-webpack-plugin", "5.6.0"))
        }
    }
}

tasks.named<Copy>("wasmJsProcessResources") {
    // index.html is owned by HtmlWebpackPlugin (see generateHtmlTemplate). Excluding it here
    // prevents a second copy from landing in dist/ alongside the HtmlWebpackPlugin output.
    exclude("index.html")
}

tasks.withType<KotlinWebpack>().configureEach {
    dependsOn(generateWebpackPublicPathConfig, generateHtmlTemplate)
}

