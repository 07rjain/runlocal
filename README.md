# RunLocal

A small native Android app for chatting with models on your phone. Two tabs: **Chat** and **Models**. No account, analytics, prompt uploads, or cloud inference. Internet is used for requested Hugging Face model setup and optional daily GitHub release checks in configured builds; downloaded models run locally afterwards.

**Current candidate: 1.0.0-rc3** · Android 12+ · ARM64 phones. This is a development release candidate, pending physical-device acceptance testing. The source is available under Apache-2.0; a stable v1 APK release has not been published.

## Use it

This repository contains source code; APKs are distributed separately through [GitHub Releases](https://github.com/07rjain/runlocal/releases) when published. For a local development build, install `dist/runlocal-1.0.0-rc3-arm64.apk` after running the build and packaging steps below. Model weights are downloaded separately. The APK retains the existing app ID, signing certificate, and a higher version code so it can update earlier development builds without uninstalling them.

1. In **Models**, paste a public Hugging Face model or supported LoRA URL and tap **Add model**. Required files are selected, downloaded, verified, and installed automatically. This can use mobile data. Downloads have pause/resume controls and storage/RAM checks.
2. In **Chat**, select an installed model at the top and send a prompt. Continue the conversation in the same screen. Image models return images in the chat, with a Save image action. Image prompts are independent generations; they do not automatically carry forward previous images.
3. Open **☰** to switch between saved chats; **＋** starts a new one. Hold a conversation in history to delete it. Changing a model starts a separate chat when the current chat has messages. Existing single-conversation history is migrated on first launch of this candidate.
4. **Mic** uses Android's on-device speech recognizer to dictate into the composer. **Read aloud** uses installed offline voices. Language-pack availability depends on the phone.
5. The single message bar contains **＋ tools**, message input, **Mic**, and send (or image cancel). Attachments and image options are under **＋**, along with app update settings.
6. Below the text model selector, tap **Processing → CPU only / Use GPU**. This choice is saved per model. GPU requests acceleration from the phone’s driver; failures do not silently change to CPU. Image generation remains CPU-only.
7. With an SD 1.x image model selected, **＋ → Attach starting image** attaches a starting image for image-to-image generation. **＋ → Image options** opens optional image settings. A prompt alone uses the defaults.

History, drafts, generated images, and model assignments are stored privately on the device. Replies remain attached to the conversation that started them when you switch chats during generation. Image chats retain their component/LoRA selection; removing a required file produces an error instead of silently substituting another model. Text inference uses the model’s chat template with proper user/assistant roles. A native engine/conversation is reused only for its owning chat and processor; switching chats/processors, a failed decode, or rolling the context window recreates it. Replay includes up to six earlier valid entries and the new message. Context is capped at 2,048 tokens; output is capped at 512 tokens, with thinking disabled. Long prompts/replies can still exceed a model’s context limit.

## Model support

| Model type | Current support |
| --- | --- |
| Text chat | Compatible `.litertlm` containers with explicit CPU/GPU choice through LiteRT-LM 0.18.1; local import and public Hugging Face setup. Repository links choose the smallest generic candidate; direct file links choose that file. Device-specific NPU variants are excluded. Container checks do not prove runtime/operator compatibility. |
| Text-to-image | A complete SD 1.x checkpoint through stable-diffusion.cpp. Automatic setup includes a known quantized SD 1.5 recipe and supported adapters. |
| Image-to-image | SD 1.x with an attached input image. |
| MOSS Image SFT | Experimental component pipeline and automatic dependency setup for supported LoRAs. Full phone generation has not been verified. |
| Speech-to-text / text-to-speech | Installed Android offline speech services; custom downloaded speech models are not supported. |
| Video, GGUF text, Cactus-native inference, `.task`, ONNX | Not supported by the installed runtimes. Recognizable local files can still be listed with compatibility labels. |

Arbitrary Hugging Face repositories are not interchangeable. Unsupported architectures, gated/private downloads, multi-shard Diffusers pipelines, and automatic conversion are not implemented. The app does not execute repository Python code. Model licenses and access terms apply separately.

The example `https://huggingface.co/axiomofmind/Moss-image-nsfw-uncensored-lora` is an **adapter**, requiring its MOSS base, Qwen text encoder, VAE, and tokenizer. Its automatic recipe totals about 9.35 GB; the conservative RAM budget can exceed a phone's available capacity. The app can identify the recipe without establishing that it will run on a Motorola Edge 60 Pro. A smaller SD 1.5 starter is an explicit alternative in Models.

## LFM3 loading fix in rc2

The reported `UNIMPLEMENTED / Failed to load model from buffer` happened with the previous LiteRT-LM **0.8.0** runtime. The [LFM3 export card](https://huggingface.co/litert-community/LFM3-1.7B-LiteRT) and its `litertlm_manifest.json` specify **0.18.1** as the minimum runtime, and list CPU/GPU support for the mixed-int4 file. Rc2 upgrades to that version, adapts the Kotlin APIs/compiler, includes the optional OpenCL native-library declarations required on Android, and isolates LFM3’s running convolution state between chats. Model-load errors are explained separately from context/memory/GPU errors. This fixes the identified version mismatch; successful inference with the exact weights on a Motorola Edge 60 Pro remains unverified.

The Cactus Android build/bindings were reviewed. This `.litertlm` file is run by LiteRT-LM, not the Cactus engine; a Cactus integration would not replace the format-specific runtime upgrade. No Cactus code was added.

## Existing local models

RunLocal models appear automatically. On launch and when opening Models, the app scans accessible saved locations and adopts existing persisted Android document grants. When no model is selected, an existing app model is selected automatically. If none is available, a small supported text model or recognizable complete image-checkpoint candidate from an accessible connected folder can be imported and selected automatically. Imports create an app-private copy; original files are preserved. Previously associated imported copies are reused.

Connect a shared model folder once through **Connect another model folder**. Android may require this initial permission. **Import model files** supports multiple file selections when Android restricts folder access. RunLocal cannot enumerate another app's private storage, obtain grants given only to Cactus, or bypass scoped storage. Cactus downloads appear only when they are in an accessible shared/exported location. Cactus-native bundles can be identified but cannot run with the current engines.

Scanning is bounded to 5,000 metadata entries per location, depth 12, and 16 MB total header reads (128 KB per candidate). Partial scans report limits. Disconnect removes a location/grant, not source files or imported copies. No broad all-files storage permission is requested.

Manual image component assignment and removal are under **Models → Advanced**. A local adapter still requires a matching base model. APKs contain no model weights.

## Build and verify

Requirements: JDK 17+, Android SDK platform 36, NDK 28.2.13676358, CMake 3.22.1, and `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir`. Pinned native revisions are recorded in `native-deps.lock`.

```sh
./scripts/bootstrap-native.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
  -Prunlocal.abis=arm64-v8a --max-workers=2
python3 scripts/package_apk.py \
  app/build/outputs/apk/debug/app-arm64-v8a-debug.apk \
  --previous dist/runlocal-0.3.1-arm64.apk --output-dir dist
```

Omit `--previous` for a first independent build. Default builds include ARM64 and x86_64; select `-Prunlocal.abis=x86_64` for an emulator build. Never distribute APKs built with Android Studio's `android.injected.build.abi` flag: it can mark them `testOnly`, which the normal Android installer rejects.

The packaging script checks standalone/non-test-only status, identity, ABI, APK signature, signer continuity when supplied, and 16 KB ZIP/native ELF alignment. It writes versioned APKs, current aliases, checksums, and `dist/INSTALLATION-CHECKS.json`. Development APKs use a local debug signer; independently built APKs use your machine's signer and cannot update someone else's signed installation.

Tests cover container inspection, Hugging Face resolution, resumable verified downloads, bounded discovery, and conversation isolation/persistence. Optional live network checks and host-native inference tests are excluded by default:

```sh
./gradlew :app:testDebugUnitTest -PliveModelCheck=true -Prunlocal.abis=arm64-v8a
```

See [the rc3 validation record](docs/VALIDATION-rc3.txt) for the candidate's exact results. Compilation and unit tests do not establish successful model inference on a phone. The local emulator cannot run with the current host's missing KVM support; Android UI, speech, grants, service lifecycle, image outputs, and thermal/performance behavior still need device testing.

## Releases and update notifications

[CHANGELOG.md](CHANGELOG.md) records the development versions. [AGENTS.md](AGENTS.md) explains the stack, architecture, invariants and validation workflow for coding agents. [RELEASING.md](RELEASING.md) covers signed/minified APKs, protected signing secrets, versioning, draft releases and update metadata.

Official builds embed their GitHub repository. Users get a foreground notice and, with notification permission, a notification for newer published **stable** releases. Checks run about once a day when enabled; Android may delay background jobs. They can be disabled in **history → App updates**, which also has a manual check. Updates open the GitHub release page for download and user-approved installation. The default release repository is `07rjain/runlocal`; older installed builds must first update to one containing this feature. Local inference remains usable offline. No chats or model files are sent during checks.

## Source and release preparation

See [V1-READINESS.md](V1-READINESS.md) for the acceptance checklist and publication prerequisites, and [CONTRIBUTING.md](CONTRIBUTING.md) for development guidance. The selected repository is https://github.com/07rjain/runlocal. The source is published here; a production APK release is still pending. Application source is licensed under Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Third-party components and model weights retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Code map:

- `MainActivity.kt`: two-tab UI, history drawer, model selection, composer, offline speech, job reconciliation.
- `ChatRepository.kt`: atomic conversation files, selection, drafts, model snapshots, idempotent reply completion.
- `ModelSetupView.kt` / `PhoneModelLibraryView.kt`: URL setup and existing/discovered model library.
- `HuggingFaceModels.kt` / `ModelFileDownloader.kt` / `ModelDownloadService.kt`: pinned setup plans, hashes, downloads, caching, resume.
- `PhoneModelScanner.kt` / `PhoneModelLibrary.kt`: bounded discovery, Android grants, catalogue and imported-copy associations.
- `ModelStore.kt` / `ImageStore.kt` / `ModelInspector.kt`: app-private model files and structural validation.
- `LocalEngine.kt`: serial CPU/GPU LiteRT-LM text adapter behind `TextRuntime`.
- `ImageGenerationService.kt` / `NativeDiffusion.kt` / `cpp/diffusion_jni.cpp`: isolated CPU diffusion process, notifications/cancellation and PNG output.

RunLocal uses established inference engines instead of writing a runtime from scratch. Cactus Chat was investigated during prototyping; its app and engine have not been incorporated. A future Cactus adapter requires separate integration, compatibility testing, and license review.
