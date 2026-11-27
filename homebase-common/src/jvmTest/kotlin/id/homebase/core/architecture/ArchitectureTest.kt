package id.homebase.core.architecture

import androidx.compose.runtime.Composable
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.classes
import com.lemonappdev.konsist.api.ext.list.functions
import com.lemonappdev.konsist.api.ext.list.properties
import com.lemonappdev.konsist.api.ext.list.withParentInterfaceNamed
import com.lemonappdev.konsist.api.ext.list.withAnnotationOf
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureTest {
    @Test
    fun `UiState classes should be data classes`() {
        Konsist.scopeFromProject()
            .classes()
            .withNameEndingWith("UiState")
            .assertTrue { it.hasDataModifier }
    }

    @Test
    fun `ffmpeg is launched only through FFmpegBinaryManager`() {
        Konsist.scopeFromProject()
            .files
            .filter { it.path.replace('\\', '/').contains("/homebase-api/src/jvmMain/") }
            .filter { !it.hasNameEndingWith("FFmpegBinaryManager") }
            .assertFalse(
                additionalMessage = "A raw ProcessBuilder( skips the GCONV_PATH guard that stops the " +
                    "Linux ffmpeg SIGSEGV. Use FFmpegBinaryManager.processBuilder."
            ) {
                it.text.contains("ProcessBuilder(")
            }
    }

    @Test
    fun `Forward and moment share stream the payload to disk instead of reading it whole`() {
        val wholeFileRead = Regex("""[Rr]eader\s*\.\s*bytes\s*\(""")
        val guarded = productionFiles
            .filter {
                val path = it.path.replace('\\', '/')
                path.contains("/homebase-chat/src/commonMain/") && it.hasNameEndingWith("ChatMessageSenderService") ||
                    path.contains("/homebase-core/src/commonMain/") && it.hasNameEndingWith("MomentDetailViewModel")
            }
        assertTrue(guarded.size >= 2, "guard matched no files; update the path filter")
        guarded.assertFalse(
            additionalMessage = "PayloadReader.bytes reads the whole payload and throws " +
                "PayloadTooLargeException over the render limit. Use PayloadDownloadService.exportToTemp " +
                "(or renderBytes for small JSON)."
        ) { it.text.contains(wholeFileRead) }
    }

    private companion object {
        // Production code only (tests build descriptors and thumbnails to check them), parsed once for every guard here.
        val productionFiles by lazy {
            val main = Regex("""/src/[a-zA-Z]*[Mm]ain/""")
            Konsist.scopeFromProject().files.filter { main.containsMatchIn(it.path.replace('\\', '/')) }
        }

        fun productionFilesExcept(names: Set<String>) = productionFiles.filter { it.name !in names }.also {
            assertTrue(it.size > 100, "guard matched too few files; update the path filter")
        }
    }

    @Test
    fun `Every payload read goes through PayloadReader`() {
        // The providers' reads are internal to homebase-api; this holds the rest: the cache's raw accessors, and api itself.
        val providers = listOf("DriveFileProvider", "DriveFileProviderCached", "DriveFileHttpProvider", "PeerFileByGlobalTransitProvider")
        val providerType = Regex("""\b(${providers.joinToString("|")})\b""")
        val read = Regex(
            """\.\s*(getPayloadBytes\w*|getThumbBytes\w*|streamPayloadDecrypted\w*|streamPayloadBody|prefetchPayload\w*|""" +
                """get\w+OverPeerByGlobalTransitId|read(Payload|Thumb|HlsChunk)Through|streamDecrypted)\s*\(""",
        )
        productionFilesExcept(providers.toSet() + "PayloadReader").assertFalse(
            additionalMessage = "Read payloads and thumbnails through PayloadReader (bytes, thumb, stream, toFile, " +
                "writePlayable, resendSource); the temporal drive API is the one read outside it.",
        ) { it.text.contains(read) && it.text.contains(providerType) }
    }

    @Test
    fun `Decrypted payloads reach disk only through PayloadReader and PayloadDownloadService`() {
        // AlbumSaver copies a finished file into the album the person picked: the one deliberate plaintext copy.
        val writers = setOf("PayloadReader", "PayloadDownloadService", "AlbumSaver")
        val decryptedRead = Regex("""[Rr]eader\s*\.\s*(bytes|stream|writePlayable|downloadHlsAsMp4)\s*\(""")
        val fileWrite = Regex("""\.\s*(writeBytes\w*|writeDownload|writeStream|writeText)\s*\(""")
        productionFilesExcept(writers).assertFalse(
            additionalMessage = "A decrypted payload must not be written to a file by hand: export it through " +
                "PayloadDownloadService (exportToTemp, exportPlayable) into a swept folder.",
        ) { it.text.contains(decryptedRead) && it.text.contains(fileWrite) }
    }

    @Test
    fun `Only the upload builder writes descriptor content`() {
        // ServerFile opens sealed descriptors on arrival; the video step learns its facts while transcoding.
        val writers = setOf("UserUploadBuilder", "DescriptorContentV1", "PayloadDescriptor", "VideoPayloadProcessor", "ServerFile")
        val builds = Regex("""DescriptorContentV1\s*\(|descriptorContentFrom[A-Z]\w*\s*\(""")
        val assignment = Regex("""\bdescriptorContent\s*=(?!=)\s*([^\n,)]+)""")
        val copyThrough = Regex("""^(null|[\w?.!]*\.descriptorContent|(UserUploadBuilder|DescriptorContentSeal)\.\w+\(.*)$""")
        productionFilesExcept(writers).assertFalse(
            additionalMessage = "Descriptor content is built by UserUploadBuilder (UPLOADS.md section 4); " +
                "anywhere else may only copy a payload's descriptorContent through.",
        ) { file ->
            file.text.contains(builds) ||
                assignment.findAll(file.text).any { !copyThrough.matches(it.groupValues[1].trim()) }
        }
    }

    @Test
    fun `Only the upload builder makes an upload's thumbnails`() {
        // Contact and profile photos and the video poster have their own sizes; the previews make a tiny only.
        // MediaBenchmarks times the pipeline on an iPhone and uploads nothing.
        val allowed = setOf(
            "UploadImages", "VideoPayloadProcessor", "ContactsProvider", "ProfileAvatarEditViewModel",
            "LinkPreviewPayloadBuilder", "LocationPreviewPayloadBuilder", "MediaBenchmarks",
        )
        val pipeline = Regex("""\b(createMediaImages|createThumbnails|createImageThumbnail|createTinyPreview)\s*\(""")
        productionFiles
            .filter { it.name !in allowed }
            .filter { !it.path.replace('\\', '/').contains("/id/homebase/api/image/") }
            .assertFalse(
                additionalMessage = "An upload's thumbnails and tiny come from UserUploadBuilder (UPLOADS.md section 4).",
            ) { it.text.contains(pipeline) }
    }

    @Test
    fun `No hardcoded strings in Composables`() {
        Konsist.scopeFromProject()
            .files
            .filter { !it.hasNameEndingWith("Test") && !it.hasNameEndingWith("Example") && !it.hasNameStartingWith("Developer") }
            .functions()
            .withAnnotationOf(Composable::class)
            .assertFalse {
                it.text.contains(Regex("""Text\s*\(\s*"[^"]*"""")) ||
                        it.text.contains(Regex("""Text\s*\(\s*text\s*=\s*"[^"]*""""))
            }
    }

    /**
     * The media upload spine (encrypt or stage, enqueue, seed the cache, write the optimistic row) lives once, in
     * `UploadService`; the exceptions are other designs or recovery plumbing, not copies of it.
     */
    @Test
    fun `uploads with payloads are built and encrypted only by UploadService`() {
        // Recovery plumbing: re-encrypts a text overflow among payloads that are already encrypted.
        val bundleCallers = setOf(
            "ChatMessageSenderService.updateMessage", "ChatMessageSenderService.resendAsCreate",
            "ConversationService.updateConversationInternal",
        )
        val requestBuilders = bundleCallers + setOf(
            // Recovery plumbing: re-sends or re-attaches payloads that are already encrypted.
            "ResendHelpers.buildResendUpdateRequest", "DriveOutboxUploader.retryAsUpdate",
            // Own designs: WebDrop encrypts under the link's key for an anonymous recipient; Filesync sends with live
            // progress, re-encrypts on a rejected hash and seals keys for peers.
            "WebDropService.buildWebDrop", "FilesyncUploader.create", "FilesyncMemberWrites.add",
        )
        val bundleCall = Regex("""\.\s*(encrypt|stage)Bundle\s*\(""")
        val request = Regex("""\b(UploadFileRequest|UpdateFileByUniqueIdRequest)\s*\(""")
        val payloadsArgument = Regex("""\bpayloads\s*=\s*([^,\n]+)""")
        val emptyPayloads = Regex("""^(emptyList\(\)|listOf\(\)|null)$""")
        fun buildsWithPayloads(text: String): Boolean = request.findAll(text).any { match ->
            var depth = 1
            var end = match.range.last + 1
            while (depth > 0 && end < text.length) {
                when (text[end]) { '(' -> depth++; ')' -> depth-- }
                end++
            }
            // Every `payloads =` in the call, a nested manifest's included: one that isn't empty is enough.
            payloadsArgument.findAll(text.substring(match.range.last + 1, end)).any { !emptyPayloads.matches(it.groupValues[1].trim()) }
        }
        Konsist.scopeFromProject()
            .files
            .filter { !it.hasNameEndingWith("Test") && !it.path.contains("/test/") && !it.path.contains("Test/") }
            .filter { it.nameWithExtension != "UploadService.kt" }
            .functions()
            .assertFalse(
                additionalMessage = "An upload with payloads goes through UploadService.upload/updateFile: it encrypts " +
                    "(or stages a public post), enqueues, seeds the cache and writes the optimistic row."
            ) {
                val name = "${it.containingFile.nameWithExtension.removeSuffix(".kt")}.${it.name}"
                (name !in bundleCallers && bundleCall.containsMatchIn(it.text)) ||
                    (name !in requestBuilders && buildsWithPayloads(it.text))
            }
    }

    /**
     * Efficacy guard for the non-blocking login (#1231). The connect sequence must resolve the
     * drive registry from the local index and let [AuthConnectionCoordinator]'s background
     * reconcile do the server half — otherwise every login, warm restores included, queues the
     * WebSocket connect and profile load behind a `getFileHeaderByUid` round-trip.
     *
     * This is a source-level rule because the invariant lives in a call-site ARGUMENT, and the
     * first attempt at this fix passed `deferServerReconcile = !headless` — which is `false` for
     * an ordinary Android launcher launch (`startsHeadless = supportsBackgroundWake`, corrected
     * only once `promoteToForeground()` runs) and so blocked exactly as before. Every unit test
     * still passed against that no-op. Any condition here reintroduces that bug.
     */
    @Test
    fun `AuthConnectionCoordinator never blocks the connect sequence on the registry server fetch`() {
        Konsist.scopeFromProject()
            .files
            .filter { it.hasNameEndingWith("AuthConnectionCoordinator") }
            .assertFalse(
                additionalMessage = "The connect sequence must call " +
                    "driveRegistry.bootstrap(deferServerReconcile = true) unconditionally (issue #1231). " +
                    "A bare bootstrap() or a conditional argument blocks login on a server round-trip; " +
                    "`headless` in particular cannot distinguish a launcher launch from an FCM wake at " +
                    "this point. Callers needing the authoritative set use awaitRegistryReconcile()."
            ) { file ->
                file.text.contains(Regex("""bootstrap\s*\((?!\s*deferServerReconcile\s*=\s*true\s*\))"""))
            }
    }

    /**
     * Session-lifetime guard (#1237). Each background reconcile in [AuthConnectionCoordinator] can
     * mount or prune drives, so one still in flight at logout would act on a session being torn
     * down — and a second login could inherit the first user's work. The cancels must sit at the
     * top of `disconnect()`, before `refreshWsSubscription.cancel()` and `driveRegistry.stop()`
     * remove what those jobs depend on.
     *
     * Source-level because [AuthConnectionCoordinator] isn't constructible in a JVM test (its own
     * `AwaitAuthRestoredTest` documents that). The empty-match guard keeps a renamed field from
     * turning the rule into a silent pass.
     */
    @Test
    fun `AuthConnectionCoordinator cancels every reconcile job in disconnect`() {
        Konsist.scopeFromProject()
            .files
            .filter { it.hasNameEndingWith("AuthConnectionCoordinator") }
            .assertTrue(
                additionalMessage = "Every *ReconcileJob field must be cancelled in disconnect() " +
                    "(issue #1237), before the collaborators it uses are stopped — otherwise an " +
                    "in-flight reconcile outlives the session and mounts/prunes drives into a dead one."
            ) { file ->
                val jobs = Regex("""\bvar\s+(\w+ReconcileJob)\b""")
                    .findAll(file.text)
                    .map { it.groupValues[1] }
                    .toSet()
                val disconnect = file.functions().firstOrNull { it.name == "disconnect" }?.text.orEmpty()
                jobs.isNotEmpty() && jobs.all { disconnect.contains("$it?.cancel()") }
            }
    }

    /**
     * Single-gateway guard (#1447). A peer's name, avatar and profile card come from
     * `ContactInfoGateway`, which serves a known contact from the synced Contacts drive and is the
     * one place allowed to fall back to the peer's `/pub/profile` + `/pub/image`. `internal`
     * already hides both providers from every other Gradle module; this rule additionally polices
     * homebase-api, where they are visible.
     *
     * Matched as an import of either provider plus `.getPublicProfile(` / `.getPublicImage(` call
     * shapes. A rule on the `/pub/profile` / `/pub/image` strings, or on the bare type name, only
     * finds the ~15 files that name them in KDoc. The allowed files are the gateway, the two
     * providers themselves, the DI wiring, and the two lifecycle owners that sit below the gateway
     * (logout cache teardown, and the websocket publicProfileContentPublished invalidation).
     */
    @Test
    fun `Public profile fetching is confined to the contact gateway`() {
        val gatewayAndBelow = setOf(
            "ContactInfoGateway",
            "PublicProfileProvider",
            "PublicProfileProviderCached",
            "ApiModule",
            "YouAuthFlowManager",
            "OwnerSessionRepository",
        )
        Konsist.scopeFromProject()
            .files
            .filter { !it.hasNameEndingWith("Test") }
            .filter { it.name !in gatewayAndBelow }
            .assertFalse(
                additionalMessage = "Contact name/avatar/profile must be read through " +
                    "ContactInfoGateway (issue #1447), never through PublicProfileProvider / " +
                    "PublicProfileProviderCached. Use displayName(), avatarBytes() or " +
                    "profileCard(); the peer /pub/profile + /pub/image fallback lives inside the " +
                    "gateway and nowhere else."
            ) { file ->
                file.text.contains(
                    Regex("""import\s+id\.homebase\.api\.client\.profile\.PublicProfileProvider"""),
                ) ||
                    file.text.contains(Regex("""\.\s*getPublicProfile\s*\(""")) ||
                    file.text.contains(Regex("""\.\s*getPublicImage\s*\("""))
            }
    }

    @Test
    fun `Do not allow calling close on httpClient`() {
        Konsist.scopeFromProject()
            .files
            .filter { !it.hasNameEndingWith("Test") }
            .assertFalse(
                additionalMessage = "HttpClient is managed by Koin DI and should not be manually closed"
            ) { file ->
                file.text.contains(Regex("""httpClient\s*\.\s*close\s*\("""))
            }
    }

    /**
     * An [id.homebase.core.session.IdentityScoped] type is registered only inside
     * `scope(IdentitySessionQualifier)`, so a bare `by inject()` — which resolves from `_root_` —
     * throws NoDefinitionFoundException the first time the property is read. `inject()` is lazy,
     * so the throw lands wherever the value is first touched rather than at construction: in
     * #1584 that was a `lifecycleScope.launch` with no handler, so sharing an image into New
     * Moment killed the process on a shipped release.
     *
     * Resolve through the identity scope instead — `identitySession.get<T>()` / `getOrNull<T>()`
     * — and inject IdentitySessionScope, which is root-registered.
     */
    @Test
    fun `IdentityScoped types are never injected from the root scope`() {
        // Production types only: the scope-mechanics test fixtures carry the marker too, and
        // their names are generic enough to match unrelated production properties.
        val identityScopedTypes = Konsist.scopeFromProject()
            .files
            .filter { !it.hasNameEndingWith("Test") }
            .classes()
            .withParentInterfaceNamed("IdentityScoped")
            .map { it.name }
            .toSet()

        // A renamed or moved marker must fail loudly rather than turn this rule vacuously green.
        assertTrue(identityScopedTypes.isNotEmpty(), "no IdentityScoped types found — has the marker moved?")

        Konsist.scopeFromProject()
            .files
            .filter { !it.hasNameEndingWith("Test") }
            .properties()
            // delegateName carries the call text — "inject()", or "inject(qualifier)".
            .filter { it.delegateName?.substringBefore("(") == "inject" }
            .assertFalse(
                additionalMessage = "Identity-scoped types are not in the root scope: `by inject()` " +
                    "throws NoDefinitionFoundException on first read. Inject IdentitySessionScope " +
                    "and resolve with identitySession.get<T>() at the point of use (see #1584)."
            ) { property ->
                property.type?.name in identityScopedTypes
            }
    }

    @Test
    fun `View models that take an ExtendPermissionViewModel close it`() {
        val owners = Konsist.scopeFromProject().classes().filter { c ->
            c.primaryConstructor?.parameters?.any { it.type.name == "ExtendPermissionViewModel" } == true
        }
        assertTrue(owners.isNotEmpty())
        owners.assertTrue(additionalMessage = "It's in no ViewModelStore: without addCloseable its collectors outlive the owner.") { c ->
            c.primaryConstructor!!.parameters.filter { it.type.name == "ExtendPermissionViewModel" }
                // An AutoCloseable adapter hands the job to whoever owns it by forwarding close.
                .all { c.text.contains("addCloseable(${it.name})") || c.text.contains("${it.name}.close()") }
        }
    }

    @Test
    fun `Every expect declaration states the contract all four platforms meet`() {
        val production = Konsist.scopeFromProduction()
        val documentedTypes = (production.classes() + production.interfaces() + production.objects())
            .filter { it.hasKDoc }
            .map { it.name }
            .toSet()
        class Expect(val name: String, val path: String, val documented: Boolean)
        val expects = production.functions().filter { it.hasExpectModifier }.map { f ->
            // A factory's contract is the type it returns.
            Expect(f.name, f.containingFile.path, f.hasKDoc || f.returnType?.name?.removeSuffix("?") in documentedTypes)
        } + production.properties().filter { it.hasExpectModifier }.map { Expect(it.name, it.containingFile.path, it.hasKDoc) } +
            production.classes().filter { it.hasExpectModifier }.map { Expect(it.name, it.containingFile.path, it.hasKDoc) } +
            production.interfaces().filter { it.hasExpectModifier }.map { Expect(it.name, it.containingFile.path, it.hasKDoc) } +
            // Konsist reads no modifiers on an object.
            production.objects().filter { Regex("""\bexpect\s+object\b""").containsMatchIn(it.text) }
                .map { Expect(it.name, it.containingFile.path, it.hasKDoc) }
        // A renamed modifier API must fail loudly rather than turn this rule vacuously green.
        assertTrue(expects.size > 100, "found only ${expects.size} expect declarations")

        val missing = expects.filterNot { it.documented }.map { "${it.path.substringAfter("/src/")}: ${it.name}" }
        assertTrue(
            missing.isEmpty(),
            "These expect declarations have no KDoc stating what every platform's actual must do " +
                "(CLAUDE.md, \"Platform-specific code\": one contract):\n" + missing.joinToString("\n"),
        )
    }
}
