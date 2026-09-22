@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.core.util

// Compose bounds are relative to #ComposeApp, so the fixed-position element adds its viewport offset (the safe-area inset).
fun showHtmlOverlay(el: JsAny, leftCss: Double, topCss: Double, widthCss: Double, heightCss: Double): Unit = js(
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
