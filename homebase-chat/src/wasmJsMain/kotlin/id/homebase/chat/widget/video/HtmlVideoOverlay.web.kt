@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.chat.widget.video

/*
 * DOM bridge for web video playback.
 *
 * Compose/wasmJs renders into a <canvas>, so a real HTML5 <video> has to be a separate DOM element
 * positioned over the composable's on-screen bounds. We append it to `document.body` with
 * `position: fixed` (NOT inside `#ComposeApp` — that element is owned by `ComposeViewport`, and a
 * child appended there is not laid out: it reports `offsetParent: null` / `0x0`). Compose's
 * `boundsInWindow()` is relative to `#ComposeApp`, so [setVideoOverlayBounds] adds `#ComposeApp`'s
 * viewport offset (the safe-area inset; zero on desktop) to convert to fixed/viewport coordinates.
 * A high z-index keeps it above the canvas; native `controls` give play/seek/volume for free.
 *
 * Byte payloads cross to JS as Base64, the same idiom as FFmpegBridge.web.kt / WebSqlDriver
 * (a direct Uint8Array bridge is a possible follow-up if large-clip playback proves slow).
 */

/** Create a hidden fixed-position <video> (controls on) appended to <body>; returns the handle. */
internal fun createVideoOverlay(muted: Boolean): JsAny = js(
    """{
        var v = document.createElement('video');
        v.setAttribute('playsinline', '');
        v.controls = true;
        v.muted = muted;
        v.style.position = 'fixed';
        v.style.objectFit = 'contain';
        v.style.background = 'black';
        v.style.zIndex = '2147483000';
        v.style.display = 'none';
        document.body.appendChild(v);
        return v;
    }"""
)

/** Position/size the element in viewport CSS px (Compose bounds + #ComposeApp offset) and reveal it. */
internal fun setVideoOverlayBounds(
    el: JsAny,
    leftCss: Double,
    topCss: Double,
    widthCss: Double,
    heightCss: Double,
): Unit = js(
    """{
        var app = document.getElementById('ComposeApp');
        var ox = 0, oy = 0;
        if (app) { var ar = app.getBoundingClientRect(); ox = ar.left; oy = ar.top; }
        el.style.left = (leftCss + ox) + 'px';
        el.style.top = (topCss + oy) + 'px';
        el.style.width = widthCss + 'px';
        el.style.height = heightCss + 'px';
        el.style.display = 'block';
    }"""
)

internal fun setVideoOverlaySrc(el: JsAny, url: String): Unit = js(
    "{ el.src = url; el.load(); }"
)

internal fun playVideoOverlay(el: JsAny): Unit = js(
    "{ var p = el.play(); if (p && typeof p.catch === 'function') p.catch(function () {}); }"
)

/** Notify [cb] with (currentTimeSec, durationSec) on data-ready / playing / timeupdate. */
internal fun addVideoOverlayProgressListener(el: JsAny, cb: (Double, Double) -> Unit): Unit = js(
    """{
        var emit = function () { cb(el.currentTime || 0, el.duration || 0); };
        el.addEventListener('loadeddata', emit);
        el.addEventListener('playing', emit);
        el.addEventListener('timeupdate', emit);
    }"""
)

/** Notify [cb] once the <video> reaches the end of the clip (drives "Watch again"). */
internal fun addVideoOverlayEndedListener(el: JsAny, cb: () -> Unit): Unit = js(
    "{ el.addEventListener('ended', function () { cb(); }); }"
)

/** Seek back to the start and resume playback (the "Watch again" action). */
internal fun replayVideoOverlay(el: JsAny): Unit = js(
    """{
        try { el.currentTime = 0; } catch (e) {}
        var p = el.play(); if (p && typeof p.catch === 'function') p.catch(function () {});
    }"""
)

/** Pause, detach the source (so the blob can be revoked) and remove the element from the DOM. */
internal fun removeVideoOverlay(el: JsAny): Unit = js(
    """{
        try { el.pause(); } catch (e) {}
        try { el.removeAttribute('src'); el.load(); } catch (e) {}
        if (el.parentNode) el.parentNode.removeChild(el);
    }"""
)

internal fun viewportHeightPx(): Double = js("window.innerHeight")

/** Base64 -> Blob object URL with the given [mimeType] (for the <video> src). */
internal fun bytesToObjectUrl(base64: String, mimeType: String): String = js(
    """{
        var bin = atob(base64);
        var arr = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
        return URL.createObjectURL(new Blob([arr], { type: mimeType }));
    }"""
)

internal fun revokeObjectUrlJs(url: String): Unit = js("{ URL.revokeObjectURL(url); }")
