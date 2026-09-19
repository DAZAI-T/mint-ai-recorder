#include <jni.h>
#include <chrono>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Wraps a whisper_context together with benchmark bookkeeping (the JNI long handle
// points at this, not directly at whisper_context*, so we have somewhere to stash
// the native-side timing that RTF is computed from).
struct BridgeContext {
    whisper_context *ctx = nullptr;
    jlong lastInferenceMs = 0;
};

// Runs whisper_full with the shared param set (greedy, no timest-print/special tokens),
// timing just the inference call. Shared by nativeTranscribe and nativeTranscribeSegments
// so the two entry points can't drift apart on decoding params.
int runFull(BridgeContext *bridge, const float *samples, int nSamples, int nThreads, const char *language) {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.language = language;
    params.translate = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.no_context = true;
    params.single_segment = false;

    const auto t0 = std::chrono::steady_clock::now();
    int rc = whisper_full(bridge->ctx, params, samples, nSamples);
    const auto t1 = std::chrono::steady_clock::now();
    bridge->lastInferenceMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    return rc;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_gijiroku_benchmark_WhisperBridge_nativeInit(JNIEnv *env, jobject /*thiz*/, jstring jModelPath) {
    const char *modelPath = env->GetStringUTFChars(jModelPath, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // phase 1 measures CPU-only inference; GPU/NNAPI backends are future work

    whisper_context *ctx = whisper_init_from_file_with_params(modelPath, cparams);
    env->ReleaseStringUTFChars(jModelPath, modelPath);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed");
        return 0;
    }

    auto *bridge = new BridgeContext();
    bridge->ctx = ctx;
    return reinterpret_cast<jlong>(bridge);
}

extern "C" JNIEXPORT void JNICALL
Java_com_gijiroku_benchmark_WhisperBridge_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto *bridge = reinterpret_cast<BridgeContext *>(ctxPtr);
    if (bridge == nullptr) return;
    if (bridge->ctx != nullptr) whisper_free(bridge->ctx);
    delete bridge;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gijiroku_benchmark_WhisperBridge_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong ctxPtr, jfloatArray jSamples, jint nThreads, jstring jLanguage) {
    auto *bridge = reinterpret_cast<BridgeContext *>(ctxPtr);
    if (bridge == nullptr || bridge->ctx == nullptr) {
        return env->NewStringUTF("");
    }

    jsize nSamples = env->GetArrayLength(jSamples);
    jfloat *samples = env->GetFloatArrayElements(jSamples, nullptr);
    const char *language = env->GetStringUTFChars(jLanguage, nullptr);

    int rc = runFull(bridge, samples, nSamples, nThreads, language);

    env->ReleaseStringUTFChars(jLanguage, language);
    env->ReleaseFloatArrayElements(jSamples, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed rc=%d", rc);
        return env->NewStringUTF("");
    }

    std::string result;
    const int nSegments = whisper_full_n_segments(bridge->ctx);
    for (int i = 0; i < nSegments; ++i) {
        result += whisper_full_get_segment_text(bridge->ctx, i);
    }
    return env->NewStringUTF(result.c_str());
}

// Same decoding pass as nativeTranscribe, but returns per-segment timestamps too (needed to
// merge transcript text with speaker-diarization segments by time — design.md 3章のパイプライン).
// Encoded as "t0ms\tt1ms\ttext\n" per segment rather than a JNI object array, since that's far
// less JNI plumbing for the same information; Kotlin parses it back in WhisperBridge.transcribeSegments.
extern "C" JNIEXPORT jstring JNICALL
Java_com_gijiroku_benchmark_WhisperBridge_nativeTranscribeSegments(
        JNIEnv *env, jobject /*thiz*/, jlong ctxPtr, jfloatArray jSamples, jint nThreads, jstring jLanguage) {
    auto *bridge = reinterpret_cast<BridgeContext *>(ctxPtr);
    if (bridge == nullptr || bridge->ctx == nullptr) {
        return env->NewStringUTF("");
    }

    jsize nSamples = env->GetArrayLength(jSamples);
    jfloat *samples = env->GetFloatArrayElements(jSamples, nullptr);
    const char *language = env->GetStringUTFChars(jLanguage, nullptr);

    int rc = runFull(bridge, samples, nSamples, nThreads, language);

    env->ReleaseStringUTFChars(jLanguage, language);
    env->ReleaseFloatArrayElements(jSamples, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed rc=%d", rc);
        return env->NewStringUTF("");
    }

    std::string result;
    const int nSegments = whisper_full_n_segments(bridge->ctx);
    for (int i = 0; i < nSegments; ++i) {
        // whisper.cpp segment timestamps are in 10ms units, not ms.
        int64_t t0ms = whisper_full_get_segment_t0(bridge->ctx, i) * 10;
        int64_t t1ms = whisper_full_get_segment_t1(bridge->ctx, i) * 10;
        std::string text = whisper_full_get_segment_text(bridge->ctx, i);
        // tabs/newlines would corrupt the line-based encoding above; segments shouldn't
        // contain them, but sanitize defensively rather than trust that.
        for (char &c : text) {
            if (c == '\t' || c == '\n') c = ' ';
        }
        result += std::to_string(t0ms) + "\t" + std::to_string(t1ms) + "\t" + text + "\n";
    }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_gijiroku_benchmark_WhisperBridge_nativeLastInferenceMs(JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto *bridge = reinterpret_cast<BridgeContext *>(ctxPtr);
    return bridge != nullptr ? bridge->lastInferenceMs : 0;
}
