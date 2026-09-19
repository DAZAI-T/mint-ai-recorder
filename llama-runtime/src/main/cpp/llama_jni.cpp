#include <algorithm>
#include <jni.h>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

namespace {

std::once_flag backend_once;

struct ModelSession {
    llama_model *model;
    int context_size;
    int threads;
};

void throw_java(JNIEnv *env, const char *message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

class UtfChars {
public:
    UtfChars(JNIEnv *env, jstring value)
        : env_(env), value_(value), chars_(env->GetStringUTFChars(value, nullptr)) {}
    ~UtfChars() { if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_); }
    const char *get() const { return chars_; }
private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_;
};

ModelSession *from_handle(jlong handle) {
    return reinterpret_cast<ModelSession *>(static_cast<intptr_t>(handle));
}

bool format_prompt(
        llama_model *model,
        const char *system_prompt,
        const char *user_prompt,
        std::string &result) {
    const char *chat_template = llama_model_chat_template(model, nullptr);
    llama_chat_message messages[] = {
        {"system", system_prompt},
        {"user", user_prompt}
    };
    int size = llama_chat_apply_template(chat_template, messages, 2, true, nullptr, 0);
    if (size <= 0) return false;
    std::vector<char> buffer(static_cast<size_t>(size) + 1);
    size = llama_chat_apply_template(
        chat_template, messages, 2, true, buffer.data(), static_cast<int32_t>(buffer.size()));
    if (size <= 0) return false;
    result.assign(buffer.data(), static_cast<size_t>(size));
    return true;
}

bool tokenize(llama_model *model, const std::string &prompt, std::vector<llama_token> &tokens) {
    const llama_vocab *vocab = llama_model_get_vocab(model);
    const int count = -llama_tokenize(
        vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (count <= 0) return false;
    tokens.resize(static_cast<size_t>(count));
    return llama_tokenize(
        vocab,
        prompt.data(),
        static_cast<int32_t>(prompt.size()),
        tokens.data(),
        count,
        true,
        true) >= 0;
}

bool decode_tokens(llama_context *context, const std::vector<llama_token> &tokens, int batch_size) {
    for (size_t offset = 0; offset < tokens.size(); offset += batch_size) {
        const int count = std::min<int>(batch_size, static_cast<int>(tokens.size() - offset));
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data() + offset), count);
        if (llama_decode(context, batch) != 0) return false;
    }
    return true;
}

std::string token_piece(const llama_vocab *vocab, llama_token token) {
    std::vector<char> buffer(256);
    int count = llama_token_to_piece(
        vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    if (count < 0) {
        buffer.resize(static_cast<size_t>(-count));
        count = llama_token_to_piece(
            vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    }
    return count > 0 ? std::string(buffer.data(), static_cast<size_t>(count)) : std::string();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_gijiroku_benchmark_LlamaNativeBridge_loadModelNative(
        JNIEnv *env,
        jclass,
        jstring model_path_value,
        jint context_size,
        jint requested_threads) {
    std::call_once(backend_once, [] { llama_backend_init(); });
    UtfChars model_path(env, model_path_value);
    if (!model_path.get()) return 0;

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(model_path.get(), params);
    if (!model) {
        throw_java(env, "Qwenモデルを読み込めませんでした");
        return 0;
    }
    const int available_threads = static_cast<int>(std::max(2u, std::thread::hardware_concurrency()));
    auto *session = new (std::nothrow) ModelSession {
        model,
        static_cast<int>(context_size),
        std::max(2, std::min(static_cast<int>(requested_threads), available_threads))
    };
    if (!session) {
        llama_model_free(model);
        throw_java(env, "Qwenセッションを作成できませんでした");
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gijiroku_benchmark_LlamaNativeBridge_countTokensNative(
        JNIEnv *env,
        jclass,
        jlong handle,
        jstring system_prompt_value,
        jstring user_prompt_value) {
    ModelSession *session = from_handle(handle);
    if (!session) {
        throw_java(env, "Qwenセッションは終了しています");
        return -1;
    }
    UtfChars system_prompt(env, system_prompt_value);
    UtfChars user_prompt(env, user_prompt_value);
    if (!system_prompt.get() || !user_prompt.get()) return -1;
    std::string formatted;
    if (!format_prompt(session->model, system_prompt.get(), user_prompt.get(), formatted)) {
        throw_java(env, "Qwenの会話テンプレートを適用できませんでした");
        return -1;
    }
    std::vector<llama_token> tokens;
    if (!tokenize(session->model, formatted, tokens)) {
        throw_java(env, "要約対象をトークン化できませんでした");
        return -1;
    }
    return static_cast<jint>(tokens.size());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_gijiroku_benchmark_LlamaNativeBridge_generateNative(
        JNIEnv *env,
        jclass,
        jlong handle,
        jstring system_prompt_value,
        jstring user_prompt_value,
        jint max_tokens) {
    ModelSession *session = from_handle(handle);
    if (!session) {
        throw_java(env, "Qwenセッションは終了しています");
        return nullptr;
    }
    UtfChars system_prompt(env, system_prompt_value);
    UtfChars user_prompt(env, user_prompt_value);
    if (!system_prompt.get() || !user_prompt.get()) return nullptr;

    std::string formatted;
    if (!format_prompt(session->model, system_prompt.get(), user_prompt.get(), formatted)) {
        throw_java(env, "Qwenの会話テンプレートを適用できませんでした");
        return nullptr;
    }
    std::vector<llama_token> prompt_tokens;
    if (!tokenize(session->model, formatted, prompt_tokens)) {
        throw_java(env, "要約対象をトークン化できませんでした");
        return nullptr;
    }
    if (static_cast<int>(prompt_tokens.size()) + max_tokens > session->context_size) {
        throw_java(env, "要約チャンクがQwenのコンテキスト上限を超えています");
        return nullptr;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(session->context_size);
    context_params.n_batch = 512;
    context_params.n_ubatch = 512;
    context_params.n_threads = session->threads;
    context_params.n_threads_batch = session->threads;
    llama_context *context = llama_init_from_model(session->model, context_params);
    if (!context) {
        throw_java(env, "Qwenの実行用メモリを確保できませんでした");
        return nullptr;
    }
    if (!decode_tokens(context, prompt_tokens, 512)) {
        llama_free(context);
        throw_java(env, "Qwenで要約対象を処理できませんでした");
        return nullptr;
    }

    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    std::string response;
    response.reserve(4096);
    for (int generated = 0; generated < max_tokens; ++generated) {
        const llama_token token = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        response += token_piece(vocab, token);
        llama_token next = token;
        llama_batch batch = llama_batch_get_one(&next, 1);
        if (llama_decode(context, batch) != 0) {
            llama_sampler_free(sampler);
            llama_free(context);
            throw_java(env, "Qwenの要約生成中にエラーが発生しました");
            return nullptr;
        }
    }
    llama_sampler_free(sampler);
    llama_free(context);
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_gijiroku_benchmark_LlamaNativeBridge_closeModelNative(
        JNIEnv *,
        jclass,
        jlong handle) {
    ModelSession *session = from_handle(handle);
    if (!session) return;
    llama_model_free(session->model);
    delete session;
}
