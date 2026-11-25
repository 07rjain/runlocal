# Offline speech models and phone testing

RunLocal uses one model library and one Chat interface for text, voice and image models. The Underdog Woof recipe includes the English Juno and Mira voices. See the [catalog guide](MODEL-CATALOG.md) for other supported voice families.

## Use a voice in Chat

1. Install the rc10 ARM64 candidate over the previous signed candidate to keep chats and models.
2. Open **Models → Add model → Speak text → Underdog Woof**. Read the license summary and tap **Download**. The 40.6 MB weights download separately and may use mobile data. Keep RunLocal open during download. You can resume a paused download from this panel.
3. Woof appears alongside installed text/image models in the library. Tap **Use in Chat**, then choose **Juno** or **Mira**. You can also choose either voice from the model picker at the top of Chat.
4. Type English text into the normal message bar and tap **play**. RunLocal speaks it and saves the text in this voice conversation. Speech is limited to 2,000 characters per reading.
5. Tap **Play again** on the reply to hear it again, or **Text** to view the spoken text. Audio is generated again for replay; temporary WAV files are not kept as permanent attachments. The conversation retains its model and voice. Selecting a different voice after messages exist starts a separate conversation.
6. While speech is preparing or playing, the send/play control becomes **stop**. Leaving the app also cancels speech. Measured load/generation/audio durations appear in the speech status when available; they are not chat token rates.
7. To read a text-model reply, tap its **speaker**. Choose that reading voice under **＋ tools → Read-aloud voice**; **Read message aloud** reads the draft. The **microphone** remains Android on-device dictation in every mode.
8. In **Models**, use the Voice filter and **⋮ → Remove model** to remove the app's downloaded copy. Old voice conversations are retained and explicitly require their original model to be reinstalled for replay. Removal resets the separate read-aloud preference to Android voices; it does not substitute a different voice in an existing speech conversation.

Voice by [Underdog Woof](https://underdog.ai) remains visibly linked in the composer when using Woof. The license permits non-commercial use with credit; commercial use needs separate permission.

## Try Kitten TTS 2 Pico

In the development build, open **Models → Add model → Speak text → Kitten TTS 2 Pico**. Download the 9.5 MB English INT4 recipe, select Eleanor, Edith, Otis or Frank, and send **“Hello there. This is a test of the Kitten voice.”** Replay the result in airplane mode after the download completes.

The edition uses preset English voices and a CMUdict-based pronunciation dictionary. It spells out unknown words and reads digits individually. KittenML’s reference English frontend also has Misaki and a neural fallback, so pronunciations can differ. Voice cloning and the larger Kitten TTS 2 edition are outside this adapter. The Linux JVM ran RunLocal’s adapter with the pinned files and produced non-silent 24 kHz mono PCM for all four voices. Android playback needs a phone test. [Kitten TTS 2 Pico model card](https://huggingface.co/KittenML/kitten-tts-2-pico/tree/a5632deac7d92f6178db814d933eba746a97f2dc)

## BlueTTS 3 research

The publisher calls this release **BlueV3**. Its INT8 preset-voice path uses a duration predictor, text encoder, vector estimator, and vocoder. Those four ONNX graphs total about 197 MB before the voice style and pronunciation data. BlueTTS’s upstream Python runtime produced non-silent 44.1 kHz audio with the pinned English files and the Noa voice on Linux, with networking blocked during inference. [BlueV3 model card](https://huggingface.co/notmax123/BlueV3-onnx/tree/8410bbfe508a9b84a967324ecf3caf5f655326c9)

Rc10 ships a portable Rust build of the polyg2p English frontend and an experimental BlueV3 Noa preset in **Models → Add model → Speak text**. The bundle includes separate OpenRAIL-M vocoder terms and CC BY 4.0 voice styles. [Frontend spec](https://huggingface.co/notmax123/BlueV3-onnx/blob/8410bbfe508a9b84a967324ecf3caf5f655326c9/frontend/FRONTEND.md) · [Bundle license inventory](https://huggingface.co/notmax123/BlueV3-onnx/blob/8410bbfe508a9b84a967324ecf3caf5f655326c9/LICENSES.md)

## Phone acceptance

Repeat both voices in airplane mode with Wi-Fi off. Test send/replay, model/voice selection after restart, switching chats during synthesis, stopping before playback, backgrounding, audio focus interruptions, denied microphone permission, paused-download resume and model removal/reinstallation. Verify previous text/image chats and downloaded models survive the update. Check narrow screens, a large font setting, keyboard open, and long model names.

Record the phone model, Android version, voice, displayed durations, whether airplane-mode playback worked, and the complete error if it failed. Microphone dictation depends on Android’s installed offline service and language pack. Downloaded Whisper and Zipformer models transcribe short mono 16-bit PCM WAV files through **Chat → ＋ → Transcribe an audio file**.

Woof's download size differs from its runtime RAM requirement. The publisher reports roughly 402–601 MB peak for its desktop NumPy implementation. RunLocal checks a conservative free-memory budget and releases its loaded native text engine on the serial worker before starting downloaded speech. Other apps and image models can still consume RAM.

## Runtime architecture and adding another model family

- `SpeechModelSpec`/`SpeechModelCatalog` describe model IDs, pinned revisions, file sizes/SHA-256 hashes, supported voices/languages, memory budgets, credits and license terms. A recipe may contain multiple data files.
- `SpeechModelStore` owns app-private downloads, partial-file resume, integrity verification, the atomic ready marker and removal. `ModelLibraryView` shows installed models of all supported types; setup and discovery panels open on demand. Only the main process reads/writes the voice-selection preferences. The speech process receives model/voice IDs and reads model/ready files directly.
- `SpeechBackend` defines synthesis into PCM WAV, cooperative cancellation and measured results. `SpeechBackends` maps a stable backend ID to an implementation. Add another runtime adapter here, implement its tokenizer/normalizer/audio decoder requirements, and register only validated recipes.
- `SpeechChatTurn` completes/fails only the originating pending reply. A `speech:<model-id>:<voice-id>` model key preserves exact voice identity without changing existing chat JSON fields. A startup cancellation fence prevents a queued engine-close callback from starting audio after stop/background.
- `SpeechService` runs one synthesis at a time in `:speech`, bounds text size, checks installation/voice/memory, handles audio focus, plays local audio and cleans up temporary files. `SpeechClient` routes replies by request ID and stops/unbinds when the Activity leaves the foreground. No prompts/audio are sent to a remote endpoint.
- Woof's adapter starts Chaquopy/Python 3.13 lazily in the speech process, with single-threaded NumPy/OpenBLAS. The reviewed reference runtime is bundled in the APK; only hash-verified weights download at runtime. Cancellation checks were added around inference steps. Model objects are released after each synthesis; Python/native libraries may remain resident in the bound/cached speech process.

For a compatible export, follow the [catalog guide](MODEL-CATALOG.md). For a new family, follow the [model adapter policy](../AGENTS.md) and test offline synthesis, interrupted downloads, cancellation and process death with the exact files.

## License and build

Voice by [Underdog Woof](https://underdog.ai). Bundled Woof code and downloaded weights are under the [Underdog Non-Commercial License 1.0](../app/src/main/python/underdog_woof/LICENSE), with [NOTICE](../app/src/main/python/underdog_woof/NOTICE) retained. Published audio must credit Underdog Woof; commercial use requires a separate written license. These files are separate from RunLocal's Apache-2.0 application code.

The Android build requires Python 3.13 on PATH as well as Java 21 and the documented Android tools. If needed, pass `-Prunlocal.buildPython=/absolute/path/to/python3.13`. Chaquopy 17.2.0 and NumPy 1.26.2 Android wheels are pinned in the Gradle configuration. The packaging verifier scans native ELF files in Chaquopy assets as well as APK `lib/` entries. Default script tests skip the adapter contract tests if host NumPy is absent; real model inference remains a separate explicit check.

Current test limitations: no working Android device/emulator is available on this host. Host inference, compile/unit tests, lint and APK inspection cannot establish successful Android startup, playback, UI behavior, memory use or thermal performance.
