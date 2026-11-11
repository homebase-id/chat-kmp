#define STB_IMAGE_RESIZE_IMPLEMENTATION
#define STB_IMAGE_RESIZE_STATIC
#include "stb_image_resize2.h"

#include <android/bitmap.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>

#define LOBES 3.0

static stbir_real sinc(stbir_real x) {
    if (x == 0) return 1;
    x *= M_PI;
    return sin(x) / x;
}

// Lanczos-3, matching LanczosResampler.kt; stb widens it with the reduction, in output pixels.
static stbir_real lanczos3(stbir_real x, stbir_real scale, void *user_data) {
    if (fabs(x) >= LOBES) return 0;
    return sinc(x) * sinc(x / LOBES);
}

static stbir_real lanczos3_support(stbir_real scale, void *user_data) {
    return LOBES;
}

// Ringing can push a premultiplied colour above its alpha, which is not a valid pixel.
static void clamp_colours_to_alpha(uint8_t *pixels, uint32_t width, uint32_t height, uint32_t stride) {
    for (uint32_t y = 0; y < height; y++) {
        uint8_t *pixel = pixels + (size_t) y * stride;
        for (uint32_t x = 0; x < width; x++, pixel += 4) {
            uint8_t alpha = pixel[3];
            if (alpha == 255) continue;
            for (int channel = 0; channel < 3; channel++) {
                if (pixel[channel] > alpha) pixel[channel] = alpha;
            }
        }
    }
}

static void throw_state(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type != NULL) (*env)->ThrowNew(env, type, message);
}

static int is_rgba_8888(const AndroidBitmapInfo *info) {
    return info->format == ANDROID_BITMAP_FORMAT_RGBA_8888;
}

JNIEXPORT void JNICALL
Java_id_homebase_imageresize_NativeResize_lanczos(JNIEnv *env, jclass type, jobject source, jobject destination) {
    AndroidBitmapInfo from, to;
    if (AndroidBitmap_getInfo(env, source, &from) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, destination, &to) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throw_state(env, "Couldn't read the bitmaps");
        return;
    }
    if (!is_rgba_8888(&from) || !is_rgba_8888(&to)) {
        throw_state(env, "Both bitmaps must be ARGB_8888");
        return;
    }
    if ((from.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL ||
        (to.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL) {
        throw_state(env, "Both bitmaps must be premultiplied");
        return;
    }

    void *input = NULL;
    void *output = NULL;
    if (AndroidBitmap_lockPixels(env, source, &input) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throw_state(env, "Couldn't lock the source pixels");
        return;
    }
    if (AndroidBitmap_lockPixels(env, destination, &output) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, source);
        throw_state(env, "Couldn't lock the destination pixels");
        return;
    }

    STBIR_RESIZE resize;
    stbir_resize_init(&resize, input, (int) from.width, (int) from.height, from.stride,
                      output, (int) to.width, (int) to.height, to.stride, STBIR_RGBA_PM, STBIR_TYPE_UINT8);
    stbir_set_filter_callbacks_new(&resize, lanczos3, lanczos3_support, NULL, lanczos3, lanczos3_support, NULL);
    int resized = stbir_resize_extended(&resize);
    if (resized && (to.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) != ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE) {
        clamp_colours_to_alpha(output, to.width, to.height, to.stride);
    }

    AndroidBitmap_unlockPixels(env, destination);
    AndroidBitmap_unlockPixels(env, source);
    if (!resized) throw_state(env, "stb_image_resize failed");
}
