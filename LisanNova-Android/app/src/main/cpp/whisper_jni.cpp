#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

static jlong nativeCreateContext(JNIEnv *env, jclass, jstring modelPath) {
    if (modelPath == nullptr) return 0;
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) return 0;
    whisper_context_params cparams = whisper_context_default_params();
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

static void nativeFreeContext(JNIEnv *, jclass, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx) whisper_free(ctx);
}

static jobjectArray nativeTranscribeChunk(JNIEnv *env, jclass, jlong ptr, jfloatArray audio, jint nThreads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx || !audio) return nullptr;

    const jsize n = env->GetArrayLength(audio);
    if (n <= 0) return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    if (!samples) return nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "tr";
    params.translate = false;
    params.n_threads = nThreads > 0 ? nThreads : 2;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_context = true;
    params.single_segment = false;
    params.temperature = 0.0f;
    params.temperature_inc = 0.2f;

    const int rc = whisper_full(ctx, params, samples, n);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (rc != 0) return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);

    const int count = whisper_full_n_segments(ctx);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(count, stringClass, nullptr);
    for (int i = 0; i < count; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const char *txt = whisper_full_get_segment_text(ctx, i);
        std::string row = std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + (txt ? txt : "");
        jstring value = env->NewStringUTF(row.c_str());
        env->SetObjectArrayElement(out, i, value);
        env->DeleteLocalRef(value);
    }
    return out;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = env->FindClass("app/lisannova/engine/WhisperBridge");
    if (!clazz) return JNI_ERR;
    JNINativeMethod methods[] = {
        {"createContext", "(Ljava/lang/String;)J", reinterpret_cast<void *>(nativeCreateContext)},
        {"freeContext", "(J)V", reinterpret_cast<void *>(nativeFreeContext)},
        {"transcribeChunk", "(J[FI)[Ljava/lang/String;", reinterpret_cast<void *>(nativeTranscribeChunk)}
    };
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
