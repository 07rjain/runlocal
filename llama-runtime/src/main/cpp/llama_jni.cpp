#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <chrono>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr int context_size = 2048;
constexpr int output_limit = 384;

struct Session {
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
};

void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

std::string bytes(JNIEnv* env, jbyteArray value) {
    std::string result(env->GetArrayLength(value), '\0');
    env->GetByteArrayRegion(value, 0, static_cast<jsize>(result.size()), reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read the chat message.");
    return result;
}

std::string render(const llama_model* model, const std::vector<std::string>& contents) {
    std::vector<llama_chat_message> messages;
    for (size_t i = 0; i < contents.size(); ++i)
        messages.push_back({ i % 2 == 0 ? "user" : "assistant", contents[i].c_str() });
    const auto* tmpl = llama_model_chat_template(model, nullptr);
    if (!tmpl) throw std::runtime_error("Choose an instruct GGUF with a built-in chat template.");
    const int length = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
    if (length <= 0 || length > 65536) throw std::runtime_error("This GGUF chat template is unsupported or the prompt is too long.");
    std::string result(length + 1, '\0');
    if (llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, result.data(), result.size()) != length)
        throw std::runtime_error("Could not render this GGUF chat template.");
    result.resize(length);
    return result;
}

void stream(JNIEnv* env, jobject listener, jmethodID update, const std::string& text) {
    auto buffer = env->NewByteArray(text.size());
    if (!buffer) throw std::runtime_error("Could not allocate a chat response.");
    env->SetByteArrayRegion(buffer, 0, text.size(), reinterpret_cast<const jbyte*>(text.data()));
    if (!env->ExceptionCheck()) env->CallVoidMethod(listener, update, buffer);
    env->DeleteLocalRef(buffer);
    if (env->ExceptionCheck()) throw std::runtime_error("Could not deliver a chat response.");
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_pocket_local_LlamaNative_open(JNIEnv* env, jobject, jbyteArray path) {
    try {
        const auto filename = bytes(env, path);
        static const bool initialized = [] { llama_backend_init(); return true; }();
        (void) initialized;
        auto session = std::make_unique<Session>();
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        session->model.reset(llama_model_load_from_file(filename.c_str(), model_params));
        if (!session->model) throw std::runtime_error("Could not load this chat GGUF with llama.cpp.");
        auto context_params = llama_context_default_params();
        context_params.n_ctx = context_size;
        context_params.n_batch = context_size;
        context_params.n_threads = 4;
        context_params.n_threads_batch = 4;
        context_params.no_perf = false;
        session->context.reset(llama_init_from_model(session->model.get(), context_params));
        if (!session->context) throw std::runtime_error("Could not allocate the GGUF chat context.");
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& error) {
        fail(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_app_pocket_local_LlamaNative_generate(JNIEnv* env, jobject, jlong handle, jobjectArray history, jobject listener) {
    try {
        auto& session = *reinterpret_cast<Session*>(handle);
        auto* ctx = session.context.get();
        const auto* vocab = llama_model_get_vocab(session.model.get());
        const auto start = Clock::now();
        std::vector<std::string> messages;
        for (int i = 0, count = env->GetArrayLength(history); i < count; ++i) {
            auto value = static_cast<jbyteArray>(env->GetObjectArrayElement(history, i));
            messages.push_back(bytes(env, value));
            env->DeleteLocalRef(value);
        }
        const auto prompt = render(session.model.get(), messages);
        std::vector<llama_token> tokens(context_size);
        const int count = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true);
        if (count <= 0 || count >= context_size - 1) throw std::runtime_error("The prompt exceeds the GGUF chat context length.");
        auto klass = env->GetObjectClass(listener);
        auto update = env->GetMethodID(klass, "onText", "([B)V");
        env->DeleteLocalRef(klass);
        if (!update) return nullptr;

        llama_memory_clear(llama_get_memory(ctx), true);
        llama_perf_context_reset(ctx);
        if (llama_decode(ctx, llama_batch_get_one(tokens.data(), count)) != 0)
            throw std::runtime_error("llama.cpp could not evaluate the prompt.");
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        if (!sampler) throw std::runtime_error("Could not initialize the GGUF sampler.");
        double first_token_ms = -1;
        int generated = 0;
        std::string response;
        const int limit = std::min(output_limit, context_size - count - 1);
        for (int i = 0; i < limit; ++i) {
            auto token = llama_sampler_sample(sampler.get(), ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            if (generated++ == 0) first_token_ms = std::chrono::duration<double, std::milli>(Clock::now() - start).count();
            char piece[1024];
            const int length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (length < 0) throw std::runtime_error("An unusually large token exceeded the output buffer.");
            response.append(piece, length);
            if (length > 0) stream(env, listener, update, response);
            if (i + 1 < limit && llama_decode(ctx, llama_batch_get_one(&token, 1)) != 0)
                throw std::runtime_error("llama.cpp could not continue decoding.");
        }
        auto performance = llama_perf_context(ctx);
        const double rate = performance.n_eval > 0 && performance.t_eval_ms > 0
            ? performance.n_eval * 1000.0 / performance.t_eval_ms : 0;
        const jdouble metrics[]{ static_cast<double>(generated), rate, first_token_ms / 1000 };
        auto output = env->NewDoubleArray(3);
        if (output) env->SetDoubleArrayRegion(output, 0, 3, metrics);
        return output;
    } catch (const std::exception& error) {
        fail(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_pocket_local_LlamaNative_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}
