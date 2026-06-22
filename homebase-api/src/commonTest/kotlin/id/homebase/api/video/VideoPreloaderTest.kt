package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.profile.FakeFileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class VideoPreloaderTest {

    private val driveId = Uuid.fromLongs(0x11L, 0x22L)
    private val fileId = Uuid.fromLongs(0xAAL, 0xBBL)
    private val payloadKey = "vid_payload"
    private val metadataKey = "metadata_payload"

    private fun playerData(stub: VideoMetadata): VideoPlayerData = VideoPlayerData(
        fileId = fileId,
        driveId = driveId,
        payloadKey = payloadKey,
        keyHeader = KeyHeader.empty(),
        descriptorContent = OdinSystemSerializer.serialize(stub),
    )

    private fun newPreloader(fake: FakeVideoPrefetchDriveAccess) =
        VideoPreloader(fake, FakeFileOperationsProvider())

    // -- Case 1: large video — descriptor stub is incomplete; the m3u8 lives in a separate payload.
    //    Expect: resolveVideoMetadata fetches the metadata payload via getPayloadBytesDecrypted
    //            (which populates DriveFileProviderCached's on-disk cache as a side effect),
    //            prefetchPayloadChunk fires for the parsed first-segment byterange,
    //            no prefetchPayload for the video payload itself.
    @Test
    fun largeVideo_fetchesMetadata_thenFirstSegment() = runTest {
        val fullMetadata = VideoMetadata(
            mimeType = "video/mp4",
            isDescriptorContentComplete = true,
            isSegmented = true,
            key = metadataKey,
            hlsPlaylist = "#EXTM3U\n#EXT-X-VERSION:4\n#EXT-X-BYTERANGE:1024@0\n#EXTINF:2.0,\nseg.ts\n",
        )
        val fake = FakeVideoPrefetchDriveAccess(
            getPayloadResponses = mapOf(metadataKey to OdinSystemSerializer.serialize(fullMetadata)),
        )
        val stub = VideoMetadata(
            mimeType = "video/mp4",
            isDescriptorContentComplete = false,
            isSegmented = true,
            key = metadataKey,
        )

        newPreloader(fake).preload(playerData(stub))

        val prefetchPayloadCalls = fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.PrefetchPayload>()
        val prefetchChunkCalls = fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.PrefetchPayloadChunk>()
        val metadataFetchCalls = fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.GetPayloadBytesDecrypted>()

        assertTrue(prefetchPayloadCalls.isEmpty(), "metadata payload is cached as a side effect of getPayloadBytesDecrypted — no explicit prefetch (calls=${fake.calls})")
        assertEquals(1, prefetchChunkCalls.size)
        assertEquals(payloadKey, prefetchChunkCalls.single().key)
        assertEquals(0L, prefetchChunkCalls.single().chunkStart)
        assertEquals(1024L, prefetchChunkCalls.single().chunkLength)

        assertNotNull(metadataFetchCalls.singleOrNull(), "metadata descriptor payload must be fetched exactly once")
        assertEquals(metadataKey, metadataFetchCalls.single().key)
    }

    // -- Case 2: small video with HLS — descriptor already carries the playlist inline, so no
    //    metadata-payload fetch is needed; only the first segment is warmed.
    @Test
    fun smallVideoWithHls_skipsMetadataPrefetch_firstSegmentOnly() = runTest {
        val stub = VideoMetadata(
            mimeType = "video/mp4",
            isDescriptorContentComplete = true,
            isSegmented = true,
            key = metadataKey,
            hlsPlaylist = "#EXTM3U\n#EXT-X-BYTERANGE:2048@0\n#EXTINF:2.0,\nseg.ts\n",
        )
        val fake = FakeVideoPrefetchDriveAccess()

        newPreloader(fake).preload(playerData(stub))

        val prefetchPayloadCalls = fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.PrefetchPayload>()
        val prefetchChunkCalls = fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.PrefetchPayloadChunk>()
        val metadataFetchCalls = fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.GetPayloadBytesDecrypted>()

        assertTrue(prefetchPayloadCalls.isEmpty(), "small video should not prefetch any full payload (calls=${fake.calls})")
        assertEquals(1, prefetchChunkCalls.size)
        assertEquals(payloadKey, prefetchChunkCalls.single().key)
        assertEquals(0L, prefetchChunkCalls.single().chunkStart)
        assertEquals(2048L, prefetchChunkCalls.single().chunkLength)
        assertTrue(metadataFetchCalls.isEmpty(), "complete descriptor must not trigger a metadata fetch")
    }

    // -- Case 3: non-HLS MP4 — the full payload is prefetched whole, no chunked call.
    @Test
    fun smallMp4_prefetchesFullPayload_noChunk() = runTest {
        val stub = VideoMetadata(
            mimeType = "video/mp4",
            isDescriptorContentComplete = true,
            isSegmented = false,
            key = metadataKey,
        )
        val fake = FakeVideoPrefetchDriveAccess()

        newPreloader(fake).preload(playerData(stub))

        val prefetchPayloadCalls = fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.PrefetchPayload>()
        val prefetchChunkCalls = fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.PrefetchPayloadChunk>()

        assertEquals(1, prefetchPayloadCalls.size)
        assertEquals(payloadKey, prefetchPayloadCalls.single().key)
        assertTrue(prefetchChunkCalls.isEmpty())
    }

    // -- Case 4: HLS playlist has no explicit `<len>@<offset>` byterange on segment 0; skip the
    //    pre-cache silently. Documents VideoPreloader's intentional narrow regex.
    @Test
    fun hlsWithoutByterange_skipsSegmentPrefetch() = runTest {
        val stub = VideoMetadata(
            mimeType = "video/mp4",
            isDescriptorContentComplete = true,
            isSegmented = true,
            key = metadataKey,
            hlsPlaylist = "#EXTM3U\n#EXTINF:2.0,\nseg.ts\n",
        )
        val fake = FakeVideoPrefetchDriveAccess()

        newPreloader(fake).preload(playerData(stub))

        val prefetchChunkCalls = fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.PrefetchPayloadChunk>()
        assertTrue(prefetchChunkCalls.isEmpty(), "no byterange tag should leave the first-segment prefetch a no-op (calls=${fake.calls})")
    }

    // -- Case 5: concurrent preload() calls for the same file serialize on a per-key mutex —
    //    both run sequentially (the second hits DriveFileProviderCached's disk cache in production,
    //    so there's still only one network fetch). Serialization matters because the surface's
    //    awaited preload() races with MediaItem's cancelled preload unwinding its finally; a
    //    tryLock-and-return would leave the surface with no progress to show.
    @Test
    fun concurrentPreloadCalls_serialize() = runTest {
        val stub = VideoMetadata(
            mimeType = "video/mp4",
            isDescriptorContentComplete = true,
            isSegmented = true,
            key = metadataKey,
            hlsPlaylist = "#EXTM3U\n#EXT-X-BYTERANGE:512@0\n#EXTINF:2.0,\nseg.ts\n",
        )
        val fake = FakeVideoPrefetchDriveAccess(prefetchDelayMs = 50L)
        val preloader = newPreloader(fake)
        val data = playerData(stub)

        val jobA = launch { preloader.preload(data) }
        val jobB = launch { preloader.preload(data) }
        jobA.join()
        jobB.join()

        val prefetchChunkCalls = fake.calls.filterIsInstance<FakeVideoPrefetchDriveAccess.Call.PrefetchPayloadChunk>()
        assertEquals(2, prefetchChunkCalls.size, "concurrent preloads serialize — both run (calls=${fake.calls})")
    }

    // -- #788: the MP4 download throws partway (reports 0.8 then fails). Progress must still
    //    settle to 1f so the bubble overlay clears instead of freezing at ~80%.
    @Test
    fun mp4PrefetchThrows_progressStillSettlesTo1f() = runTest {
        val stub = VideoMetadata(
            mimeType = "video/mp4",
            isDescriptorContentComplete = true,
            isSegmented = false,
            key = metadataKey,
        )
        val fake = FakeVideoPrefetchDriveAccess(prefetchError = RuntimeException("network died"))
        val preloader = newPreloader(fake)

        preloader.preload(playerData(stub))

        assertEquals(1f, preloader.progressFlow(fileId, payloadKey).value)
    }

    // -- #788: metadata resolution fails (incomplete stub, no metadata payload). Progress must
    //    settle to 1f so the bubble overlay clears instead of freezing at 0%.
    @Test
    fun metadataMiss_progressStillSettlesTo1f() = runTest {
        val stub = VideoMetadata(
            mimeType = "video/mp4",
            isDescriptorContentComplete = false,
            isSegmented = true,
            key = metadataKey,
        )
        val fake = FakeVideoPrefetchDriveAccess()
        val preloader = newPreloader(fake)

        preloader.preload(playerData(stub))

        assertEquals(1f, preloader.progressFlow(fileId, payloadKey).value)
    }
}
