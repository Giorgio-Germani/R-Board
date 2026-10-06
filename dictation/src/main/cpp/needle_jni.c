/* JNI bridge between app.reventor.dictation.NeedleNative and the Needle C API
 * (needle.h). The engine is process-global and not thread-safe; Kotlin serializes
 * all calls onto a single executor, so no locking here. */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "needle.h"

#define OUT_CAPACITY (64 * 1024)

static jstring take_output(JNIEnv *env, const char *out, int result) {
    if (result < 0) {
        const char *err = needle_last_error();
        jstring msg = (*env)->NewStringUTF(env, err ? err : "needle failed");
        jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (cls) (*env)->ThrowNew(env, cls, err ? err : "needle failed");
        (void)msg;
        return NULL;
    }
    return (*env)->NewStringUTF(env, out);
}

static const char *cstr_or_null(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    return (*env)->GetStringUTFChars(env, s, NULL);
}

static void release_cstr(JNIEnv *env, jstring s, const char *c) {
    if (s != NULL && c != NULL) (*env)->ReleaseStringUTFChars(env, s, c);
}

JNIEXPORT jint JNICALL
Java_app_reventor_dictation_NeedleNative_nativeLoad(JNIEnv *env, jobject thiz, jbyteArray cact) {
    (void)thiz;
    jsize n = (*env)->GetArrayLength(env, cact);
    jbyte *buf = (*env)->GetByteArrayElements(env, cact, NULL);
    int r = needle_load((const unsigned char *)buf, (unsigned long long)n);
    (*env)->ReleaseByteArrayElements(env, cact, buf, JNI_ABORT);
    return r;
}

JNIEXPORT jstring JNICALL
Java_app_reventor_dictation_NeedleNative_nativeTranscribe(
        JNIEnv *env, jobject thiz, jfloatArray pcm, jstring language, jstring keywords) {
    (void)thiz;
    jsize samples = (*env)->GetArrayLength(env, pcm);
    jfloat *p = (*env)->GetFloatArrayElements(env, pcm, NULL);
    const char *lang = cstr_or_null(env, language);
    const char *kw = cstr_or_null(env, keywords);
    char *out = malloc(OUT_CAPACITY);
    jstring result = NULL;
    if (out != NULL) {
        int r = needle_transcribe(p, samples, lang, kw, 0, out, OUT_CAPACITY);
        result = take_output(env, out, r);
        free(out);
    }
    release_cstr(env, language, lang);
    release_cstr(env, keywords, kw);
    (*env)->ReleaseFloatArrayElements(env, pcm, p, JNI_ABORT);
    return result;
}

JNIEXPORT jstring JNICALL
Java_app_reventor_dictation_NeedleNative_nativeStreamProcess(
        JNIEnv *env, jobject thiz, jfloatArray pcm, jstring language, jstring keywords) {
    (void)thiz;
    jsize samples = (*env)->GetArrayLength(env, pcm);
    jfloat *p = (*env)->GetFloatArrayElements(env, pcm, NULL);
    const char *lang = cstr_or_null(env, language);
    const char *kw = cstr_or_null(env, keywords);
    char *out = malloc(OUT_CAPACITY);
    jstring result = NULL;
    if (out != NULL) {
        int r = needle_stream_transcribe_process(p, samples, lang, kw, out, OUT_CAPACITY);
        result = take_output(env, out, r);
        free(out);
    }
    release_cstr(env, language, lang);
    release_cstr(env, keywords, kw);
    (*env)->ReleaseFloatArrayElements(env, pcm, p, JNI_ABORT);
    return result;
}

JNIEXPORT jstring JNICALL
Java_app_reventor_dictation_NeedleNative_nativeStreamStop(JNIEnv *env, jobject thiz) {
    (void)thiz;
    char *out = malloc(OUT_CAPACITY);
    jstring result = NULL;
    if (out != NULL) {
        int r = needle_stream_transcribe_stop(out, OUT_CAPACITY);
        result = take_output(env, out, r);
        free(out);
    }
    return result;
}

JNIEXPORT jstring JNICALL
Java_app_reventor_dictation_NeedleNative_nativeLastError(JNIEnv *env, jobject thiz) {
    (void)thiz;
    const char *err = needle_last_error();
    return (*env)->NewStringUTF(env, err ? err : "");
}
