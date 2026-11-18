#include <jni.h>

#if defined(RUNLOCAL_MIRAGE)
#include "cactus.h"
#include <cstring>
#include <fstream>
#include <mutex>
#include <vector>
#endif

namespace {
constexpr int max_edge = 1600;

jbyteArray error(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
    return nullptr;
}

#if defined(RUNLOCAL_MIRAGE)
// The Cactus C API has process-wide state and no unload function. Retain the
// verified model bytes in the dedicated OCR process, and serialize native calls.
constexpr size_t weight_bytes = 21611043;
constexpr int max_response = 128 * 1024;
constexpr char text_only[] = R"({"sections":["text"],"fields":["bbox","score"],"max_text_lines":96,"max_tokens":16})";
std::mutex engine_mutex;
std::vector<unsigned char> weights;
bool loaded = false;

bool load(JNIEnv* env, jstring model) {
    if (!weights.empty()) return loaded;
    const char* path = env->GetStringUTFChars(model, nullptr);
    if (!path) return false;
    std::ifstream input(path, std::ios::binary);
    env->ReleaseStringUTFChars(model, path);
    if (!input) return false;

    std::vector<unsigned char> data(weight_bytes);
    if (!input.read(reinterpret_cast<char*>(data.data()), data.size())) return false;
    if (input.peek() != std::ifstream::traits_type::eof()) return false;
    weights.swap(data);
    cactus_set_threads(2);
    loaded = cactus_load(weights.data(), weights.size()) >= 0 && (cactus_models() & CACTUS_VISION);
    return loaded;
}
#endif
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_pocket_local_MirageNative_describe(JNIEnv* env, jobject, jstring model,
                                          jbyteArray rgb, jint width, jint height) {
#if defined(RUNLOCAL_MIRAGE)
    if (!model || !rgb || width < 1 || height < 1 || width > max_edge || height > max_edge ||
        env->GetArrayLength(rgb) != width * height * 3) {
        return error(env, "Invalid RGB image for Mirage.");
    }

    try {
        const std::lock_guard<std::mutex> guard(engine_mutex);
        if (!load(env, model)) return env->ExceptionCheck() ? nullptr : error(env, "Cactus could not load the installed Mirage model.");
        std::vector<char> response(max_response, 0);
        std::vector<unsigned char> pixels(width * height * 3);
        env->GetByteArrayRegion(rgb, 0, static_cast<jsize>(pixels.size()), reinterpret_cast<jbyte*>(pixels.data()));
        if (env->ExceptionCheck()) return nullptr;
        const int result = cactus_describe(pixels.data(),
                                           width, height, text_only, "en", nullptr,
                                           response.data(), response.size());
        if (result < 0) return error(env, "Mirage could not read this image.");
        const size_t count = strnlen(response.data(), response.size());
        if (count == 0 || count == response.size()) return error(env, "Mirage returned an invalid OCR response.");

        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(count));
        if (!bytes) return nullptr;
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(count), reinterpret_cast<const jbyte*>(response.data()));
        return bytes;
    } catch (const std::exception&) {
        return error(env, "The OCR runtime could not finish this image.");
    }
#else
    return error(env, "Mirage requires an ARM64 Android build.");
#endif
}
