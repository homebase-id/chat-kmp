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
    fun normalizes_gyan_windows_git_snapshot() {
        // gyan.dev Windows builds report a date-git-hash blob with a distributor
        // suffix; the About screen should show the trimmed date+git token (#1035).
        val banner = "ffmpeg version 2026-01-07-git-af6a1dd0b2-essentials_build-www.gyan.dev Copyright (c) 2000-2026"
        assertEquals("2026-01-07-git-af6a1dd0b2", parseFfmpegVersionBanner(banner))
    }

    @Test
    fun normalizes_johnvansickle_static_suffix() {
        val banner = "ffmpeg version 7.0.2-static https://johnvansickle.com/ffmpeg/ Copyright (c) 2000-2024"
        assertEquals("7.0.2", parseFfmpegVersionBanner(banner))
    }

    @Test
    fun leaves_martinriedl_git_describe_untouched() {
        val banner = "ffmpeg version N-122320-g38e89fe502 Copyright (c) 2000-2026 the FFmpeg developers"
        assertEquals("N-122320-g38e89fe502", parseFfmpegVersionBanner(banner))
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
