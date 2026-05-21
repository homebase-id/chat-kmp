package id.homebase.api.image

import androidx.compose.ui.graphics.ImageBitmap
import id.homebase.api.image.draw.StrokeCommand
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise
import kotlinx.coroutines.await

actual fun convertHeicToJpeg(heicBytes: ByteArray): ByteArray? = null

actual fun ByteArray.toImageBitmap(): ImageBitmap? {
    TODO("Not yet implemented")
}

actual object ImageUtils {
    actual fun resizePreserveAspect(
        srcBytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        TODO("Not yet implemented")
    }

    actual fun compressOnly(
        srcBytes: ByteArray,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        TODO("Not yet implemented")
    }

    actual fun crop(
        srcBytes: ByteArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        TODO("Not yet implemented")
    }

    actual fun rotate(
        srcBytes: ByteArray,
        degrees: Int,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        TODO("Not yet implemented")
    }

    actual fun getNaturalSize(srcBytes: ByteArray): ImageSize {
        TODO("Not yet implemented")
    }

    actual fun warpAffine(
        srcBytes: ByteArray,
        matrix9: FloatArray,
        outputWidth: Int,
        outputHeight: Int,
        fillColorArgb: Int,
        outputFormat: ImageFormat,
        quality: Int,
    ): ImageResult {
        TODO("Not yet implemented")
    }

    actual fun drawStrokes(
        srcBytes: ByteArray,
        strokes: List<StrokeCommand>,
        outputFormat: ImageFormat,
        quality: Int,
    ): ImageResult {
        TODO("Not yet implemented")
    }

    actual fun blurBytes(
        srcBytes: ByteArray,
        radius: Int,
        outputFormat: ImageFormat,
        quality: Int,
    ): ImageResult {
        TODO("Not yet implemented")
    }

    @OptIn(ExperimentalEncodingApi::class)
    actual suspend fun rasterizeSvg(
        svgBytes: ByteArray,
        maxDim: Int,
        outputFormat: ImageFormat,
        quality: Int,
    ): ImageResult {
        // Browser-native rasterization: build a Blob from the SVG bytes,
        // load it as an Image (async — Image.onload resolves a Promise),
        // draw onto an OffscreenCanvas scaled to fit the maxDim box
        // preserving aspect, then convertToBlob → base64 round-trip.
        //
        // Base64 (instead of raw Uint8Array interop) keeps the wasm-js
        // boundary simple — strings cross cleanly without buffer copies.
        // The JS helper returns the rendered tiny+natural dimensions
        // alongside the encoded bytes in a single string, semicolon
        // delimited: "naturalW;naturalH;renderedW;renderedH;<base64>".
        val svgBase64 = Base64.encode(svgBytes)
        val mime = when (outputFormat) {
            ImageFormat.WEBP -> "image/webp"
            ImageFormat.JPEG -> "image/jpeg"
            ImageFormat.PNG -> "image/png"
            ImageFormat.BMP, ImageFormat.GIF -> "image/png" // browser canvas can't emit BMP/GIF
        }
        val result = rasterizeSvgJs(svgBase64, maxDim, mime, quality.toDouble() / 100.0).await<JsString>()
        val parts = result.toString().split(";", limit = 5)
        if (parts.size < 5) {
            throw IllegalStateException("rasterizeSvgJs returned malformed result")
        }
        val naturalW = parts[0].toInt()
        val naturalH = parts[1].toInt()
        val renderedW = parts[2].toInt()
        val renderedH = parts[3].toInt()
        val bytes = Base64.decode(parts[4])

        return ImageResult(
            bytes = bytes,
            naturalSize = ImageSize(naturalW, naturalH),
            size = ImageSize(renderedW, renderedH),
        )
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun rasterizeSvgJs(
    svgBase64: String,
    maxDim: Int,
    mime: String,
    quality: Double,
): Promise<JsString> = js(
    """{
        return new Promise(function(resolve, reject) {
            try {
                // Decode base64 → bytes → Blob (image/svg+xml).
                var binary = atob(svgBase64);
                var len = binary.length;
                var arr = new Uint8Array(len);
                for (var i = 0; i < len; i++) arr[i] = binary.charCodeAt(i);
                var blob = new Blob([arr], { type: 'image/svg+xml' });
                var url = URL.createObjectURL(blob);

                var img = new Image();
                img.onload = function () {
                    try {
                        var naturalW = img.naturalWidth || img.width || 320;
                        var naturalH = img.naturalHeight || img.height || 320;
                        var scale = maxDim / Math.max(naturalW, naturalH);
                        var w = Math.max(1, Math.round(naturalW * scale));
                        var h = Math.max(1, Math.round(naturalH * scale));

                        var canvas = (typeof OffscreenCanvas !== 'undefined')
                            ? new OffscreenCanvas(w, h)
                            : Object.assign(document.createElement('canvas'), { width: w, height: h });
                        var ctx = canvas.getContext('2d');
                        ctx.clearRect(0, 0, w, h);
                        ctx.drawImage(img, 0, 0, w, h);
                        URL.revokeObjectURL(url);

                        var toBlob = canvas.convertToBlob
                            ? canvas.convertToBlob({ type: mime, quality: quality })
                            : new Promise(function (res) { canvas.toBlob(res, mime, quality); });
                        toBlob.then(function (outBlob) {
                            if (!outBlob) { reject(new Error('canvas produced no blob')); return; }
                            outBlob.arrayBuffer().then(function (buf) {
                                var u8 = new Uint8Array(buf);
                                var bin = '';
                                for (var j = 0; j < u8.length; j++) bin += String.fromCharCode(u8[j]);
                                var b64 = btoa(bin);
                                resolve(naturalW + ';' + naturalH + ';' + w + ';' + h + ';' + b64);
                            }, reject);
                        }, reject);
                    } catch (e) { URL.revokeObjectURL(url); reject(e); }
                };
                img.onerror = function (e) { URL.revokeObjectURL(url); reject(e); };
                img.src = url;
            } catch (e) { reject(e); }
        });
    }"""
)