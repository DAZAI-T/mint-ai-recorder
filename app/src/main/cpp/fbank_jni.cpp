#include <jni.h>
#include <algorithm>
#include <vector>
#include "kaldi-native-fbank/csrc/online-feature.h"

// Config chosen to exactly match sherpa-onnx's FeatureExtractorConfig defaults for its
// "general" (3D-Speaker/WeSpeaker/CAM++) speaker-embedding model path — see
// speaker-embedding-extractor-general-impl.h and features.h in k2-fsa/sherpa-onnx.
// Getting these wrong doesn't crash anything; it just silently produces embeddings that
// don't discriminate speakers well, so they're spelled out here rather than left at
// kaldi-native-fbank's own (different) defaults.
static knf::FbankOptions BuildFbankOptions(int sampleRate) {
    knf::FbankOptions opts;
    opts.frame_opts.samp_freq = static_cast<float>(sampleRate);
    opts.frame_opts.frame_shift_ms = 10.0f;
    opts.frame_opts.frame_length_ms = 25.0f;
    opts.frame_opts.dither = 0.0f;
    opts.frame_opts.preemph_coeff = 0.97f;
    opts.frame_opts.remove_dc_offset = true;
    opts.frame_opts.window_type = "povey";
    opts.frame_opts.round_to_power_of_two = true;
    opts.frame_opts.snip_edges = false;
    opts.mel_opts.num_bins = 80;
    opts.mel_opts.low_freq = 20.0f;
    opts.mel_opts.high_freq = -400.0f;
    return opts;
}

// Returns a flat row-major [numFrames x 80] array (feature_dim=80 is fixed by
// BuildFbankOptions above, so Kotlin recovers numFrames as result.size / 80 without
// needing a separate return value for it).
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_gijiroku_benchmark_FbankExtractor_nativeComputeFbank(
        JNIEnv *env, jobject /*thiz*/, jfloatArray jSamples, jint sampleRate) {
    knf::OnlineFbank fbank(BuildFbankOptions(sampleRate));

    jsize n = env->GetArrayLength(jSamples);
    jfloat *samples = env->GetFloatArrayElements(jSamples, nullptr);
    fbank.AcceptWaveform(static_cast<float>(sampleRate), samples, n);
    fbank.InputFinished();
    env->ReleaseFloatArrayElements(jSamples, samples, JNI_ABORT);

    const int32_t numFrames = fbank.NumFramesReady();
    const int32_t dim = fbank.Dim();

    std::vector<float> flat(static_cast<size_t>(numFrames) * dim);
    for (int32_t i = 0; i < numFrames; ++i) {
        const float *frame = fbank.GetFrame(i);
        std::copy(frame, frame + dim, flat.begin() + static_cast<size_t>(i) * dim);
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(flat.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
    return result;
}
