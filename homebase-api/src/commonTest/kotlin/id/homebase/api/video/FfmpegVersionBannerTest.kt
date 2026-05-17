package id.homebase.api.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FfmpegVersionBannerTest {

    @Test
    fun parses_release_banner() {
        val banner = """
            ffmpeg version n6.0 Copyright (c) 2000-2023 the FFmpeg developers
            built with Apple clang version 14.0.0
        """.trimIndent()
        assertEquals("n6.0", parseFfmpegVersionBanner(banner))
    }

    @Test
    fun parses_semver_banner() {
        val banner = "ffmpeg version 6.1.1 Copyright (c) 2000-2023 the FFmpeg developers"
        assertEquals("6.1.1", parseFfmpegVersionBanner(banner))
    }

    @Test
    fun parses_android_suffixed_banner() {
        val banner = "ffmpeg version n6.0-android Copyright (c) 2000-2023 the FFmpeg developers"
        assertEquals("n6.0-android", parseFfmpegVersionBanner(banner))
    }

    @Test
    fun parses_when_banner_is_not_first_line() {
        val banner = """
            [info] preparing
            ffmpeg version 5.1.4 Copyright (c) 2000-2023 the FFmpeg developers
        """.trimIndent()
        assertEquals("5.1.4", parseFfmpegVersionBanner(banner))
    }

    @Test
    fun returns_null_for_blank_input() {
        assertNull(parseFfmpegVersionBanner(null))
        assertNull(parseFfmpegVersionBanner(""))
        assertNull(parseFfmpegVersionBanner("   \n   "))
    }

    @Test
    fun returns_null_when_no_banner_line() {
        assertNull(parseFfmpegVersionBanner("some other tool output\nno version banner here"))
    }
}
