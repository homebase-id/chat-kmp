#include "blake3/blake3.h"

#include <jni.h>
#include <string.h>

// The hasher lives in a direct ByteBuffer the caller owns, so the garbage collector frees it.
static blake3_hasher *hasher(JNIEnv *env, jobject state) {
    return (blake3_hasher *) (*env)->GetDirectBufferAddress(env, state);
}

JNIEXPORT jint JNICALL Java_id_homebase_blake3_NativeBlake3_stateSize(JNIEnv *env, jclass cls) {
    return (jint) sizeof(blake3_hasher);
}

JNIEXPORT void JNICALL Java_id_homebase_blake3_NativeBlake3_init(JNIEnv *env, jclass cls, jobject state) {
    blake3_hasher_init(hasher(env, state));
}

JNIEXPORT void JNICALL Java_id_homebase_blake3_NativeBlake3_update(
        JNIEnv *env, jclass cls, jobject state, jbyteArray data, jint offset, jint length) {
    // Before the critical get: no other JNI call is allowed until it's released.
    blake3_hasher *h = hasher(env, state);
    jsize size = (*env)->GetArrayLength(env, data);
    if (offset < 0 || length < 0 || length > size - offset) {
        jclass bounds = (*env)->FindClass(env, "java/lang/IndexOutOfBoundsException");
        (*env)->ThrowNew(env, bounds, "range outside the array");
        return;
    }
    // Critical: no copy of the input, which is what makes this fast. Hashing a few MB holds it briefly.
    jbyte *bytes = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (bytes == NULL) return;
    blake3_hasher_update(h, bytes + offset, (size_t) length);
    (*env)->ReleasePrimitiveArrayCritical(env, data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_id_homebase_blake3_NativeBlake3_finish(JNIEnv *env, jclass cls, jobject state, jbyteArray out) {
    uint8_t digest[BLAKE3_OUT_LEN];
    blake3_hasher_finalize(hasher(env, state), digest, BLAKE3_OUT_LEN);
    (*env)->SetByteArrayRegion(env, out, 0, BLAKE3_OUT_LEN, (const jbyte *) digest);
}
