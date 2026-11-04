// JNI bridge to RNNoise. Works on 48 kHz mono in frames of 480 samples, scaled to 16-bit range as
// RNNoise expects. One DenoiseState per handle: keep a handle per stream, not per frame.
#include <jni.h>
#include <stdlib.h>
#include "rnnoise.h"

#define FRAME 480

JNIEXPORT jlong JNICALL Java_com_zellahq_whisper_Denoiser_create(JNIEnv *env, jclass cls) {
    return (jlong) (intptr_t) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL Java_com_zellahq_whisper_Denoiser_destroy(JNIEnv *env, jclass cls, jlong h) {
    if (h) rnnoise_destroy((DenoiseState *) (intptr_t) h);
}

/** Denoises [n] samples (a multiple of 480) of -1..1 floats in place. Returns the mean voice probability. */
JNIEXPORT jfloat JNICALL Java_com_zellahq_whisper_Denoiser_process(JNIEnv *env, jclass cls, jlong h, jfloatArray buf, jint n) {
    DenoiseState *st = (DenoiseState *) (intptr_t) h;
    jfloat *x = (*env)->GetFloatArrayElements(env, buf, NULL);
    float frame[FRAME];
    float vad = 0;
    int frames = 0;
    for (int off = 0; off + FRAME <= n; off += FRAME) {
        for (int i = 0; i < FRAME; i++) frame[i] = x[off + i] * 32768.0f;
        vad += rnnoise_process_frame(st, frame, frame);
        for (int i = 0; i < FRAME; i++) x[off + i] = frame[i] / 32768.0f;
        frames++;
    }
    (*env)->ReleaseFloatArrayElements(env, buf, x, 0);
    return frames ? vad / frames : 0;
}
