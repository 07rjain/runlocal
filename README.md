# RunLocal

A small native Android app for chatting with models on your phone. Two tabs: **Chat** and **Models**. No account, analytics, prompt uploads, or cloud inference. Internet is used for requested Hugging Face model setup, the catalog check when browsing for models, and optional daily GitHub release checks. Downloaded models run locally.

**Candidate: 1.0.0-rc12** · Android 12+ · ARM64 phones. Physical-device acceptance is pending. Application code is Apache-2.0. The bundled Woof runtime has a separate non-commercial license. A stable v1 APK release has not been published.

## Use it

APKs are distributed through [GitHub Releases](https://github.com/07rjain/runlocal/releases). For a local development build, install `dist/runlocal-1.0.0-rc12-arm64.apk` after running the build and packaging steps below. Model weights are downloaded separately. An official APK with the existing app ID and signing certificate and a higher version code can update earlier official builds in place.

1. **Models** is a single installed-model library with All, Text, Voice, Image, OCR and Transcription filters. **Add model** opens the catalog. Pick a task to see available models, storage and RAM fit estimates, and a download action. The catalog installs verified files directly from Hugging Face. Pasting a model link, importing files and connecting folders are available under **Other ways to add a model**. Downloads can use mobile data and support pause and resume.
2. Tap the model name at the top of **Chat** to select an installed text, voice, image, OCR, or transcription model. The composer adapts to the selected model. Voice replies can be replayed and generated images can be saved. Each image prompt generates a separate image.
3. Open **☰** to switch between saved chats; **＋** starts a new one. Hold a conversation in history to delete it. Changing a model starts a separate chat when the current chat has messages. Existing single-conversation history is migrated on first launch of this candidate.
4. The **microphone** dictates into the composer using Android's on-device recognizer. In the catalog, use **Speak text** to download Woof, then choose Juno or Mira and send text to speak it. On rc9, Woof is under **Add model → Supported voice models**. The **speaker** below a text reply reads it aloud. Choose its voice under **＋ tools → Read-aloud voice**. Android language-pack availability depends on the phone.
5. The single message bar contains message input, **＋ tools**, microphone, and send/play/stop. Attachments and image options are under **＋**. App update settings are in chat history.
6. With a GPU-capable LiteRT-LM model selected, tap **＋ tools → CPU / GPU processing → CPU only / Use GPU**. The header shows the selected processor. This choice is saved per model. GPU requests acceleration from the phone’s driver; failures do not silently change to CPU. GGUF chat and image generation use the CPU.
7. With a supported SD 1.x or SDXL Base image model selected, **＋ → Attach starting image** attaches a starting image for image-to-image generation. **＋ → Image options** opens optional image settings.
8. With an **OCR** model selected, use **Add an image** or **Take a photo**. With a **Transcription** model selected, use **Add an audio file** or **Record audio**. Choose a mono 16-bit PCM WAV or record up to two minutes. Results appear in the conversation and can be copied. The picker links to the matching catalog when you need to install a model. Use the **＋** tools in a text conversation to extract text into its draft.

History, drafts, generated images, and model assignments are stored privately on the device. Replies remain attached to the conversation that started them when you switch chats during generation. Image chats retain their component/LoRA selection; removing a required file produces an error instead of silently substituting another model. Text inference uses the model’s chat template with proper user/assistant roles. A native engine/conversation is reused only for its owning chat and processor; switching chats/processors, a failed decode, or rolling the context window recreates it. Replay includes up to six earlier valid entries and the new message. Context is capped at 2,048 tokens; output is capped at 512 tokens, with thinking disabled. Long prompts/replies can still exceed a model’s context limit.

## Loading and generation performance

Android’s native launch splash uses the RunLocal icon and colors. Sending a text message shows a spinner and elapsed time, with distinct loading-model, preparing-prompt and generating stages. Responses stream into the pending reply without rebuilding the composer. Reading older messages stops automatic scrolling. Model setup/import tasks also have an indeterminate loading indicator; no artificial progress percentage is displayed.

Completed text replies include decode tokens/sec, runtime time to first token (TTFT), and total time when available. Tap the summary for output-token count, processor, model/session load, and time to first text received including loading. Native decode statistics come from LiteRT-LM or llama.cpp. Live text timing is measured with a monotonic clock. Warm sessions report zero model/session load. These values are local to each reply, persist across restarts, and are never uploaded.

In the catalog, **Details** shows the last measured timing when the model has run successfully on this phone. Download time is estimated from the active transfer. Model fit is estimated locally from memory and free storage. GPU execution still depends on the phone’s graphics driver.

The catalog checks the public GitHub list when opened and keeps the last valid copy for offline browsing. The request contains no chat data or device profile. Maintainers can [edit the model list without shipping a new APK](docs/MODEL-CATALOG.md).

## Model support

| Model type | Current support |
| --- | --- |
| Text chat | Compatible `.litertlm` exports through LiteRT-LM 0.18.1 with explicit CPU/GPU choice. Rc10 adds compatible single-file instruct GGUF exports through llama.cpp on CPU. |
| Text-to-image | Complete SD 1.x and experimental SDXL Base checkpoints through stable-diffusion.cpp on CPU. |
| Image-to-image | SD 1.x and experimental SDXL Base with an attached input image. |
| MOSS Image SFT | Experimental component pipeline and automatic dependency setup for supported LoRAs. Full phone generation has not been verified. |
| Speech-to-text | Android on-device microphone dictation and experimental Whisper/Zipformer WAV transcription. |
| Text-to-speech | Android offline voices and experimental Woof, Kitten TTS 2 Pico, BlueV3 English and Kokoro. The APK also includes a Piper adapter for compatible recipes. |
| OCR | Experimental Cactus Mirage on ARM64 and Tesseract LSTM language packs. Use **Add an image** or **Take a photo** from Chat’s **＋** menu. See [Mirage setup and validation](docs/MIRAGE-OCR.md). |
| Other formats | Video, arbitrary Cactus bundles, `.task`, and arbitrary ONNX graphs require additional runtimes or adapter contracts. |

Arbitrary Hugging Face repositories are not interchangeable. Unsupported architectures, gated/private downloads, multi-shard Diffusers pipelines, and automatic conversion are not implemented. Only reviewed, bundled runtime code executes; downloaded repositories cannot supply executable Python code. Arbitrary speech formats require a compatible adapter and are rejected. Model licenses and access terms apply separately.

The example `https://huggingface.co/axiomofmind/Moss-image-nsfw-uncensored-lora` is an **adapter**, requiring its MOSS base, Qwen text encoder, VAE, and tokenizer. Its automatic recipe totals about 9.35 GB; the conservative RAM budget can exceed a phone's available capacity. The app can identify the recipe without establishing that it will run on a Motorola Edge 60 Pro. A smaller SD 1.5 starter is an explicit alternative in Models.

## LFM3 loading fix in rc2

The reported `UNIMPLEMENTED / Failed to load model from buffer` happened with the previous LiteRT-LM **0.8.0** runtime. The [LFM3 export card](https://huggingface.co/litert-community/LFM3-1.7B-LiteRT) and its `litertlm_manifest.json` specify **0.18.1** as the minimum runtime, and list CPU/GPU support for the mixed-int4 file. Rc2 upgrades to that version, adapts the Kotlin APIs/compiler, includes the optional OpenCL native-library declarations required on Android, and isolates LFM3’s running convolution state between chats. Model-load errors are explained separately from context/memory/GPU errors. This fixes the identified version mismatch; successful inference with the exact weights on a Motorola Edge 60 Pro remains unverified.

The LFM3 `.litertlm` export uses LiteRT-LM. RunLocal’s separate Cactus adapter only reads images with the Mirage OCR model.

## Existing local models

RunLocal models appear automatically. On launch and when opening Models, the app scans accessible saved locations and adopts existing persisted Android document grants. When no model is selected, an existing app model is selected automatically. If none is available, a small supported text model or recognizable complete image-checkpoint candidate from an accessible connected folder can be imported and selected automatically. Imports create an app-private copy; original files are preserved. Previously associated imported copies are reused.

Connect a shared model folder once through **Models → Add model → Connect a model folder**. Saved locations and discovered files are under **Models → Files & folders**. Android may require this initial permission. **Import model files** supports multiple file selections when Android restricts folder access. RunLocal cannot enumerate another app's private storage, obtain grants given only to Cactus, or bypass scoped storage. Cactus downloads appear only when they are in an accessible shared/exported location. The Mirage OCR adapter installs its own pinned copy and does not import arbitrary Cactus bundles.

Scanning is bounded to 5,000 metadata entries per location, depth 12, and 16 MB total header reads (128 KB per candidate). Partial scans report limits. Disconnect removes a location/grant, not source files or imported copies. No broad all-files storage permission is requested.

Manual image component assignment and removal are under **Models → Add model → Advanced image setup** (individual file removal is also available in **Models → Image components**). A local adapter still requires a matching base model. APKs contain no model weights.

## Build and verify

Requirements include Python 3.13, JDK 21+, stable Rust managed with rustup, Android SDK platform 36, NDK 28.2.13676358, CMake 3.22.1, and `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir`. The bootstrap script installs the Rust Android targets. Pinned native revisions are recorded in `native-deps.lock`.

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

See [the rc8 update validation record](docs/VALIDATION-rc8.txt), [the rc7 UI validation record](docs/VALIDATION-rc7.txt), [earlier host inference results](docs/FEATURE-VALIDATION-2026-11-09.md) and [phone speech testing instructions](docs/SPEECH-MODELS.md) for the candidate's results. The previous startup-crash investigation is recorded in [rc5 validation](docs/VALIDATION-rc5.txt). Compilation and unit tests do not establish successful model inference on a phone. The local emulator cannot run with the current host's missing KVM support; Android UI, speech, grants, service lifecycle, image outputs, and thermal/performance behavior still need device testing.

## Website

The [RunLocal website](https://runlocal-android.vercel.app/) includes [installation and troubleshooting](https://runlocal-android.vercel.app/support) and an [assistant guide](https://runlocal-android.vercel.app/llms.txt). Vercel is connected to this GitHub repository and deploys [`website/`](website/) to production when changes are pushed to `main`. The [GitHub Pages mirror](https://07rjain.github.io/runlocal/) is published by `.github/workflows/pages.yml` when the website changes on `main`.

Run `python3 scripts/check_website.py website` to check local links and markup. The landing page checks the public GitHub Releases API for the newest published APK and keeps its bundled download link available if the API is unreachable.

## Releases and update notifications

[CHANGELOG.md](CHANGELOG.md) records the development versions. [AGENTS.md](AGENTS.md) explains the stack, architecture, invariants and validation workflow for coding agents. [RELEASING.md](RELEASING.md) covers signed/minified APKs, protected signing secrets, versioning, draft releases and update metadata.

Official builds embed their GitHub repository. Users get a foreground notice and, with notification permission, a notification for newer published **stable** releases by default. To receive development versions, enable **Include release candidates (experimental)** in **history → App updates**. Candidate notices are explicitly labeled. Rc7 and older need one manual update to rc8 or later because their checker only reads stable releases. Checks run about once a day when enabled; Android may delay background jobs. They can be disabled in **history → App updates**, which also has a manual check. Updates open the GitHub release page for download and user-approved installation. The default release repository is `07rjain/runlocal`; older installed builds must first update to one containing this feature. Local inference remains usable offline. No chats or model files are sent during checks.

## Source and release preparation

See [V1-READINESS.md](V1-READINESS.md) for the acceptance checklist and publication prerequisites, and [CONTRIBUTING.md](CONTRIBUTING.md) for development guidance. The selected repository is https://github.com/07rjain/runlocal. The source is published here; a production APK release is still pending. Original application code is licensed under Apache-2.0; bundled third-party Woof code is under the Underdog Non-Commercial License 1.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Third-party components and model weights retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Code map:

- `SpeechModels.kt` / `ModelLibraryView.kt`: supported speech catalogue, pinned downloads and one installed-model library. `SpeechChat.kt` preserves voice identity and routes speech reply completion.
- `SpeechBackend.kt` / `SpeechService.kt` / `SpeechClient.kt`: runtime adapters, serial inference in a separate process, cancellation, audio focus and local playback.
- `python/underdog_woof` / `python/runlocal_woof.py`: pinned, separately licensed Woof reference runtime and Android adapter through Chaquopy/Python 3.13 and NumPy.
- `MainActivity.kt`: two-tab UI, history drawer, model selection, composer, offline speech, job reconciliation.
- `ChatRepository.kt`: atomic conversation files, selection, drafts, model snapshots, idempotent reply completion.
- `ModelSetupView.kt` / `PhoneModelLibraryView.kt`: URL setup and existing/discovered model library.
- `HuggingFaceModels.kt` / `ModelFileDownloader.kt` / `ModelDownloadService.kt`: pinned setup plans, hashes, downloads, caching, resume.
- `PhoneModelScanner.kt` / `PhoneModelLibrary.kt`: bounded discovery, Android grants, catalogue and imported-copy associations.
- `ModelStore.kt` / `ImageStore.kt` / `ModelInspector.kt`: app-private model files and structural validation.
- `LocalEngine.kt`: serial CPU/GPU LiteRT-LM text adapter behind `TextRuntime`.
- `ImageGenerationService.kt` / `NativeDiffusion.kt` / `cpp/diffusion_jni.cpp`: isolated CPU diffusion process, notifications/cancellation and PNG output.

See the [catalog guide](docs/MODEL-CATALOG.md) for adapter contracts and the [speech guide](docs/SPEECH-MODELS.md) for voice setup and testing. Voice playback stops when leaving the app. Woof’s non-commercial license requires visible attribution and separate commercial permission.

Voice by [Underdog Woof](https://underdog.ai). Its [Underdog Non-Commercial License 1.0](app/src/main/python/underdog_woof/LICENSE) and [NOTICE](app/src/main/python/underdog_woof/NOTICE) apply separately to the bundled runtime and downloaded model.
