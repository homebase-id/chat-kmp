package id.homebase.core.ui.screens.card

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResponse
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.feed.services.ChannelDefinition
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostContent
import id.homebase.core.feed.services.PostType
import id.homebase.core.feed.services.PrimaryMediaFile
import id.homebase.core.feed.services.ReadingTimeStats
import id.homebase.core.image.ImageSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class CardPostsTest {

    private val odinId = "frodo.dotyou.cloud"
    private val anonymous = AccessControlList("anonymous")
    private val connected = AccessControlList("connected")
    private val owner = AccessControlList("owner")

    private fun file(
        fileType: Int,
        content: String?,
        acl: AccessControlList?,
        driveId: Uuid = Uuid.random(),
        userDate: Long? = null,
        created: Long = 0,
        state: FileState = FileState.Active,
        payloads: List<PayloadDescriptor>? = null,
        fileId: Uuid = Uuid.random(),
    ) = HomebaseFile(
        fileId = fileId,
        driveId = driveId,
        fileState = state,
        fileSystemType = FileSystemType.Standard,
        keyHeader = KeyHeader(iv = ByteArray(16), aesKey = SecureByteArray(ByteArray(16) { 7 })),
        fileMetadata = FileMetadata(
            created = UnixTimeUtc(created),
            payloads = payloads,
            appData = AppFileMetaData(fileType = fileType, userDate = userDate, content = content),
        ),
        serverMetadata = ServerMetadata(accessControlList = acl),
    )

    private fun definition(slug: String = "notes", showOnHomePage: Boolean = true, acl: AccessControlList? = anonymous) = file(
        FeedProtocol.ChannelDefinitionFileType,
        OdinSystemSerializer.serialize(ChannelDefinition(name = "Notes", slug = slug, showOnHomePage = showOnHomePage)),
        acl,
    )

    private fun post(
        slug: String,
        acl: AccessControlList? = anonymous,
        userDate: Long? = null,
        created: Long = 0,
        state: FileState = FileState.Active,
        content: PostContent = PostContent(id = "id-$slug", slug = slug, caption = "Caption $slug"),
        payloads: List<PayloadDescriptor>? = null,
        fileId: Uuid = Uuid.random(),
    ) = file(
        FeedProtocol.PostFileType,
        OdinSystemSerializer.serialize(content),
        acl,
        userDate = userDate,
        created = created,
        state = state,
        payloads = payloads,
        fileId = fileId,
    )

    private class FakeDrives(
        val channels: Map<Uuid, Pair<HomebaseFile?, List<HomebaseFile>>>,
        val withoutDefinition: Set<Uuid> = emptySet(),
    ) : CardPostDrives {
        val postPages = mutableMapOf<Uuid, Int>()

        override suspend fun channelIds() = channels.keys.toList() + withoutDefinition

        override suspend fun query(channelId: Uuid, request: QueryBatchRequest): QueryBatchResponse {
            if (channelId in withoutDefinition) return QueryBatchResponse(searchResults = emptyList(), hasMoreRows = false)
            val (definition, posts) = channels.getValue(channelId)
            if (definition == null) error("no read grant")
            if (request.queryParams.fileType == listOf(FeedProtocol.ChannelDefinitionFileType)) {
                return QueryBatchResponse(searchResults = listOf(definition), hasMoreRows = false)
            }
            val page = request.resultOptionsRequest.cursorState?.toInt() ?: 0
            postPages[channelId] = (postPages[channelId] ?: 0) + 1
            val size = request.resultOptionsRequest.maxRecords
            val slice = posts.drop(page * size).take(size)
            return QueryBatchResponse(
                searchResults = slice,
                cursorState = "${page + 1}",
                hasMoreRows = posts.size > (page + 1) * size,
            )
        }

        override suspend fun payloadText(channelId: Uuid, fileId: Uuid, key: String): String? = null
    }

    private fun slugs(result: List<CardPostEntry>) = result.map { it.post.href.substringAfterLast('/') }

    @Test
    fun onlyPublicPostsOnPublicHomePageChannelsAreKept() = runTest {
        val publicChannel = Uuid.random()
        val connectionsChannel = Uuid.random()
        val hiddenChannel = Uuid.random()
        val drives = FakeDrives(
            mapOf(
                publicChannel to (definition() to listOf(
                    post("public", created = 60),
                    post("connections", acl = connected, created = 50),
                    post("owner", acl = owner, created = 40),
                    post("circle", acl = AccessControlList("connected", circleIdList = listOf("0f2c1a8e5b3d4e6f9a1b2c3d4e5f6a7b")), created = 35),
                    post("deleted", state = FileState.Deleted, created = 30),
                    post("no-acl", acl = null, created = 20),
                )),
                connectionsChannel to (definition(slug = "inner", acl = connected) to listOf(post("in-inner", created = 10))),
                hiddenChannel to (definition(showOnHomePage = false) to listOf(post("hidden", created = 70))),
            ),
        )

        val result = loadCardPosts(odinId, drives)

        assertEquals(listOf("public"), slugs(result))
        assertNull(drives.postPages[connectionsChannel])
        assertNull(drives.postPages[hiddenChannel])
    }

    @Test
    fun channelsMergeNewestFirstByUserDateElseCreatedAndCapAtTwelve() = runTest {
        val a = Uuid.random()
        val b = Uuid.random()
        val drives = FakeDrives(
            mapOf(
                a to (definition(slug = "a") to (1..10).map { post("a$it", userDate = it * 10L, created = 1) }),
                b to (definition(slug = "b") to (1..10).map { post("b$it", created = it * 10L + 5) }),
            ),
        )

        val posts = loadCardPosts(odinId, drives).map { it.post }

        assertEquals(CARD_POST_LIMIT, posts.size)
        assertEquals(listOf("b10", "a10", "b9", "a9", "b8", "a8", "b7", "a7", "b6", "a6", "b5", "a5"), posts.map { it.href.substringAfterLast('/') })
        assertEquals(105L, posts.first().date)
        assertEquals(posts.sortedByDescending { it.date }, posts)
    }

    @Test
    fun aChannelPagesUntilTwelvePublicPostsSurvive() = runTest {
        val channel = Uuid.random()
        val hidden = (1..30).map { post("owner$it", acl = owner, created = 1000L - it) }
        val visible = (1..40).map { post("p$it", created = 500L - it) }
        val drives = FakeDrives(mapOf(channel to (definition() to hidden + visible)))

        val result = loadCardPosts(odinId, drives)

        assertEquals(2, drives.postPages[channel])
        assertEquals((1..12).map { "p$it" }, slugs(result))
    }

    @Test
    fun aChannelThatRunsOutStopsPaging() = runTest {
        val channel = Uuid.random()
        val drives = FakeDrives(mapOf(channel to (definition() to (1..3).map { post("p$it", created = 10L - it) })))

        assertEquals(listOf("p1", "p2", "p3"), slugs(loadCardPosts(odinId, drives)))
        assertEquals(1, drives.postPages[channel])
    }

    @Test
    fun anUnreadableChannelIsLeftOutWithoutFailingTheRest() = runTest {
        val readable = Uuid.random()
        val unreadable = Uuid.random()
        val drives = FakeDrives(
            mapOf(
                readable to (definition() to listOf(post("ok", created = 1))),
                unreadable to (null to listOf(post("never", created = 2))),
            ),
        )

        assertEquals(listOf("ok"), slugs(loadCardPosts(odinId, drives)))
    }

    @Test
    fun hrefUsesTheChannelSlugThenPostSlugOrId() {
        val channel = CardChannel("notes")
        val withSlug = assertNotNull(cardPost(odinId, channel, post("there-and-back")))
        assertEquals("https://frodo.dotyou.cloud/posts/notes/there-and-back", withSlug.post.href)

        val noSlug = assertNotNull(cardPost(odinId, channel, post("", content = PostContent(id = "abc123", slug = ""))))
        assertEquals("https://frodo.dotyou.cloud/posts/notes/abc123", noSlug.post.href)

        assertNull(cardPost(odinId, channel, post("", content = PostContent(id = "", slug = ""))))

        val publicChannel = assertNotNull(cardChannel(definition(slug = ""), ChannelDefinition(name = "Main", slug = "")))
        assertEquals("public-posts", publicChannel.slug)
        assertNull(cardChannel(definition(acl = connected), ChannelDefinition(name = "Inner", slug = "inner")))
    }

    @Test
    fun postFieldsFollowTheContract() {
        val channel = CardChannel("notes")
        val article = PostContent(
            id = "a1",
            slug = "long-read",
            type = PostType.Article,
            caption = "  A long read ",
            abstract = " What it's about ",
            readingTimeStats = ReadingTimeStats(minutes = 3.2),
        )
        val file = post("long-read", content = article, userDate = 1_700_000_000_000, created = 5)
        val built = assertNotNull(cardPost(odinId, channel, file)).post

        assertEquals(file.fileId.toString(), built.id)
        assertEquals(1_700_000_000_000, built.date)
        assertEquals("A long read", built.title)
        assertEquals("What it's about", built.excerpt)
        assertEquals(3.2, built.minutes)
        assertEquals("article", built.type)

        val tweet = assertNotNull(cardPost(odinId, channel, post("t", content = PostContent(id = "t", slug = "t", caption = " ")))).post
        assertNull(tweet.title)
        assertNull(tweet.excerpt)
        assertEquals("tweet", tweet.type)
    }

    @Test
    fun theImageIsThePrimaryMediaThumbnailOfThePostsOwnFile() {
        val fileId = Uuid.random()
        val payload = PayloadDescriptor(
            key = "pst_mdi_00",
            contentType = "video/mp4",
            lastModified = 42,
            thumbnails = listOf(ThumbnailDescriptor(pixelWidth = 400, pixelHeight = 300)),
        )
        fun media(type: String, id: String? = null) = PostContent(
            id = "m",
            slug = "m",
            type = PostType.Media,
            primaryMediaFile = PrimaryMediaFile(fileKey = "pst_mdi_00", fileId = id, type = type),
        )
        fun image(content: PostContent) = postImage(post("m", content = content, payloads = listOf(payload), fileId = fileId), content)

        val video = assertNotNull(image(media("video/mp4")))
        assertEquals(fileId, video.fileId)
        assertEquals("pst_mdi_00", video.payloadKey)
        assertEquals(42L, video.lastModified)
        assertEquals(listOf(ImageSize(400, 300)), video.availableThumbSizes)

        assertNotNull(image(media("image")))
        assertNotNull(image(media("image/jpeg", id = fileId.toString())))
        assertNotNull(image(media("image/jpeg", id = fileId.toHexString().uppercase())))
        assertNull(image(media("image/jpeg", id = Uuid.random().toString())))
        assertNull(image(media("audio/mpeg")))
        assertNull(image(media("image/jpeg").copy(primaryMediaFile = PrimaryMediaFile(fileKey = "pst_mdi_09", type = "image/jpeg"))))
        assertNull(image(PostContent(id = "m", slug = "m")))
    }
}
