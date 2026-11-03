#include <jni.h>
#include <algorithm>
#include <cstdio>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "stable-diffusion.h"
#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_ONLY_JPEG
#include "stb_image.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

namespace {
struct Callback {
    JavaVM* vm;
    jobject target;
    jmethodID method;
    std::string error;
    size_t applied_lora_tensors = 0;
    std::mutex mutex;
};
void progress(int step, int total, float, void* data) {
    auto* cb = static_cast<Callback*>(data);
    JNIEnv* env = nullptr;
    bool attached = cb->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK;
    if (attached) {
#ifdef __ANDROID__
        if (cb->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
#else
        if (cb->vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
#endif
    }
    env->CallVoidMethod(cb->target, cb->method, step, total);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) cb->vm->DetachCurrentThread();
}
void log_callback(sd_log_level_t level, const char* message, void* data) {
    if (!message) return;
    auto* cb = static_cast<Callback*>(data);
    std::lock_guard<std::mutex> guard(cb->mutex);
    if (level == SD_LOG_ERROR) cb->error = (cb->error + message).substr(0, 3000);
    const std::string entry(message);
    auto end = entry.find(") LoRA tensors have been applied");
    if (end != std::string::npos) {
        auto start = entry.rfind('(', end);
        size_t applied = 0, total = 0;
        if (start != std::string::npos && std::sscanf(entry.c_str() + start, "(%zu / %zu)", &applied, &total) == 2)
            cb->applied_lora_tensors += applied;
    }
}
std::string string_at(JNIEnv* env, jobjectArray array, int index) {
    auto value = static_cast<jstring>(env->GetObjectArrayElement(array, index));
    if (!value) return "";
    const char* utf = env->GetStringUTFChars(value, nullptr);
    if (!utf) { env->DeleteLocalRef(value); throw std::runtime_error("Could not read a generation parameter."); }
    std::string result(utf);
    env->ReleaseStringUTFChars(value, utf);
    env->DeleteLocalRef(value);
    return result;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_pocket_local_NativeDiffusion_systemInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(sd_get_system_info());
}

extern "C" JNIEXPORT void JNICALL
Java_app_pocket_local_NativeDiffusion_generate(JNIEnv* env, jobject, jobjectArray values,
                                              jint width, jint height, jint steps, jlong seed,
                                              jfloat cfg, jfloat lora_scale, jfloat strength,
                                              jobject listener) {
    Callback cb{};
    env->GetJavaVM(&cb.vm);
    cb.target = env->NewGlobalRef(listener);
    auto cls = env->GetObjectClass(listener);
    cb.method = env->GetMethodID(cls, "onProgress", "(II)V");
    env->DeleteLocalRef(cls);
    if (!cb.method || !cb.target) return;
    sd_set_log_callback(log_callback, &cb);
    sd_set_progress_callback(progress, &cb);
    try {
        if (env->GetArrayLength(values) != 11 || width < 128 || height < 128 ||
            width > 768 || height > 768 || width % 16 || height % 16 || steps < 1 || steps > 50)
            throw std::runtime_error("Invalid image-generation parameters.");
        std::vector<std::string> args;
        for (int i = 0; i < 11; ++i) args.push_back(string_at(env, values, i));
        const bool moss = args[0] == "moss";
        if ((!moss && args[0] != "sd15") || args[10].empty() ||
            (!moss && args[1].empty()) ||
            (moss && (args[2].empty() || args[3].empty() || args[4].empty() || args[5].empty())))
            throw std::runtime_error("This image pipeline is missing required model components.");
        if (moss && !args[9].empty()) throw std::runtime_error("Image-to-image is enabled only for the SD 1.x pipeline.");

        sd_ctx_params_t context_params;
        sd_ctx_params_init(&context_params);
        context_params.model_path = args[1].c_str();
        context_params.diffusion_model_path = args[2].c_str();
        context_params.llm_path = args[3].c_str();
        context_params.vae_path = args[4].c_str();
        context_params.tokenizer = args[5].c_str();
        context_params.n_threads = 4;
        context_params.backend = "cpu";
        context_params.params_backend = "cpu";
        context_params.enable_mmap = true;
        context_params.flash_attn = true;
        context_params.rng_type = STD_DEFAULT_RNG;
        context_params.sampler_rng_type = STD_DEFAULT_RNG;
        context_params.lora_apply_mode = LORA_APPLY_AT_RUNTIME;
        std::unique_ptr<sd_ctx_t, decltype(&free_sd_ctx)> context(new_sd_ctx(&context_params), free_sd_ctx);
        if (!context) throw std::runtime_error(cb.error.empty() ? "The model could not load. Check the architecture, components, and available RAM." : cb.error);
        if (!sd_ctx_supports_image_generation(context.get())) throw std::runtime_error("This model is not an image-generation pipeline.");
        const std::string version = sd_get_model_version_name(context.get());
        // Do not dispatch a foreign architecture through the wrong app profile.
        if (moss && version.find("MOSS") == std::string::npos && version.find("moss") == std::string::npos)
            throw std::runtime_error("The diffusion weights are not a MOSS image model.");
        if (!moss && version != "SD 1.x")
            throw std::runtime_error("Choose a complete SD 1.x checkpoint for this pipeline.");
        sd_img_gen_params_t params;
        sd_img_gen_params_init(&params);
        params.prompt = args[7].c_str();
        params.negative_prompt = args[8].c_str();
        params.width = width;
        params.height = height;
        params.batch_count = 1;
        params.seed = seed;
        params.strength = strength;
        params.sample_params.sample_steps = steps;
        params.sample_params.sample_method = EULER_SAMPLE_METHOD;
        params.sample_params.scheduler = DISCRETE_SCHEDULER;
        params.sample_params.guidance.txt_cfg = cfg;
        params.vae_tiling_params.enabled = true;
        params.vae_tiling_params.tile_size_w = 256;
        params.vae_tiling_params.tile_size_h = 256;
        // The runtime applies MOSS's negative-branch layer-22 skip automatically.
        sd_lora_t lora{};
        if (!args[6].empty()) {
            lora.path = args[6].c_str(); lora.multiplier = lora_scale;
            params.loras = &lora; params.lora_count = 1;
        }
        std::unique_ptr<unsigned char, decltype(&stbi_image_free)> input(nullptr, stbi_image_free);
        if (!args[9].empty()) {
            int w, h, channels;
            input.reset(stbi_load(args[9].c_str(), &w, &h, &channels, 3));
            if (!input || w != width || h != height) throw std::runtime_error("The input image must match the output dimensions.");
            params.init_image = {static_cast<uint32_t>(w), static_cast<uint32_t>(h), 3, input.get()};
        }
        sd_image_generation_result_t* raw = nullptr;
        const bool success = generate_image(context.get(), &params, &raw);
        std::unique_ptr<sd_image_generation_result_t, decltype(&free_sd_image_generation_result)> result(raw, free_sd_image_generation_result);
        if (!success || !result || result->count < 1 || !result->items[0].image.data)
            throw std::runtime_error(cb.error.empty() ? "Image generation failed. Try a smaller resolution or compatible weights." : cb.error);
        if (!cb.error.empty()) throw std::runtime_error(cb.error);
        if (!args[6].empty() && lora_scale != 0 && cb.applied_lora_tensors == 0)
            throw std::runtime_error("The selected LoRA did not apply any weights to this base model. Check that the adapter architecture and tensor names match.");
        const auto& image = result->items[0].image;
        if (!stbi_write_png(args[10].c_str(), image.width, image.height, image.channel, image.data, image.width * image.channel))
            throw std::runtime_error("Could not save the generated image. Check free storage.");
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
    }
    sd_set_progress_callback(nullptr, nullptr);
    sd_set_log_callback(nullptr, nullptr);
    env->DeleteGlobalRef(cb.target);
}
