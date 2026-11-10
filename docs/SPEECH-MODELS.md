# Offline speech models and phone testing

RunLocal rc7 uses one model library and one Chat interface for text, voice and image models. Underdog Woof is the first supported downloaded voice family, with English Juno and Mira voices. Other architectures need compatible adapters; file extensions alone do not establish support.

## Use a voice in Chat

1. Install `dist/runlocal-1.0.0-rc8-arm64.apk` over the previous candidate. Keep the existing app installed so chats and models survive. This remains a development candidate.
2. Open **Models → Add model → Supported voice models → Underdog Woof**. Read the license summary and tap **Download model**. The 40.6 MB weights download separately and may use mobile data. Keep RunLocal open; paused downloads can be resumed from this panel.
3. Woof appears alongside installed text/image models in the library. Tap **Use in Chat**, then choose **Juno** or **Mira**. You can also choose either voice from the model picker at the top of Chat.
4. Type English text into the normal message bar and tap **play**. RunLocal speaks it and saves the text in this voice conversation. Speech is limited to 2,000 characters per reading.
5. Tap **Play again** on the reply to hear it again, or **Text** to view the spoken text. Audio is generated again for replay; temporary WAV files are not kept as permanent attachments. The conversation retains its model and voice. Selecting a different voice after messages exist starts a separate conversation.
6. While speech is preparing or playing, the send/play control becomes **stop**. Leaving the app also cancels speech. Measured load/generation/audio durations appear in the speech status when available; they are not chat token rates.
7. To read a text-model reply, tap its **speaker**. Choose that reading voice under **＋ tools → Read-aloud voice**; **Read message aloud** reads the draft. The **microphone** remains Android on-device dictation in every mode.
8. In **Models**, use the Voice filter and **⋮ → Remove model** to remove the app's downloaded copy. Old voice conversations are retained and explicitly require their original model to be reinstalled for replay. Removal resets the separate read-aloud preference to Android voices; it does not substitute a different voice in an existing speech conversation.

Voice by [Underdog Woof](https://underdog.ai) remains visibly linked in the composer when using Woof. The license permits non-commercial use with credit; commercial use needs separate permission.

## Phone acceptance

Repeat both voices in airplane mode with Wi-Fi off. Test send/replay, model/voice selection after restart, switching chats during synthesis, stopping before playback, backgrounding, audio focus interruptions, denied microphone permission, paused-download resume and model removal/reinstallation. Verify previous text/image chats and downloaded models survive the update. Check narrow screens, a large font setting, keyboard open, and long model names.

Record the phone model/Android version, voice, displayed durations, whether airplane-mode playback worked, and the complete error if it failed. Speech-to-text depends on the phone's installed offline recognition service and language pack; RunLocal does not download a separate STT model or fall back to online recognition.

Woof's download size differs from its runtime RAM requirement. The publisher reports roughly 402–601 MB peak for its desktop NumPy implementation. RunLocal checks a conservative free-memory budget and releases its loaded native text engine on the serial worker before starting downloaded speech. Other apps and image models can still consume RAM.

## Runtime architecture and adding another model family

- `SpeechModelSpec`/`SpeechModelCatalog` describe model IDs, pinned revisions, file sizes/SHA-256 hashes, supported voices/languages, memory budgets, credits and license terms. A recipe may contain multiple data files.
- `SpeechModelStore` owns app-private downloads, partial-file resume, integrity verification, the atomic ready marker and removal. `ModelLibraryView` shows installed models of all supported types; setup and discovery panels open on demand. Only the main process reads/writes the voice-selection preferences. The speech process receives model/voice IDs and reads model/ready files directly.
- `SpeechBackend` defines synthesis into PCM WAV, cooperative cancellation and measured results. `SpeechBackends` maps a stable backend ID to an implementation. Add another runtime adapter here, implement its tokenizer/normalizer/audio decoder requirements, and register only validated recipes.
- `SpeechChatTurn` completes/fails only the originating pending reply. A `speech:<model-id>:<voice-id>` model key preserves exact voice identity without changing existing chat JSON fields. A startup cancellation fence prevents a queued engine-close callback from starting audio after stop/background.
- `SpeechService` runs one synthesis at a time in `:speech`, bounds text size, checks installation/voice/memory, handles audio focus, plays local audio and cleans up temporary files. `SpeechClient` routes replies by request ID and stops/unbinds when the Activity leaves the foreground. No prompts/audio are sent to a remote endpoint.
- Woof's adapter starts Chaquopy/Python 3.13 lazily in the speech process, with single-threaded NumPy/OpenBLAS. The reviewed reference runtime is bundled in the APK; only hash-verified weights download at runtime. Cancellation checks were added around inference steps. Model objects are released after each synthesis; Python/native libraries may remain resident in the bound/cached speech process.

For a new family: review its code/license, add the backend and manifest, include its notices, verify both normal and interrupted setup, test cancellation and process death, run offline synthesis with the exact weights, check all native libraries for 16 KB alignment, then record Android acceptance. Existing setup, selection and playback UI can be reused. There is no arbitrary remote-code execution or automatic model conversion.

## License and build

Voice by [Underdog Woof](https://underdog.ai). Bundled Woof code and downloaded weights are under the [Underdog Non-Commercial License 1.0](../app/src/main/python/underdog_woof/LICENSE), with [NOTICE](../app/src/main/python/underdog_woof/NOTICE) retained. Published audio must credit Underdog Woof; commercial use requires a separate written license. These files are separate from RunLocal's Apache-2.0 application code.

The Android build requires Python 3.13 on PATH as well as Java 21 and the documented Android tools. If needed, pass `-Prunlocal.buildPython=/absolute/path/to/python3.13`. Chaquopy 17.2.0 and NumPy 1.26.2 Android wheels are pinned in the Gradle configuration. The packaging verifier scans native ELF files in Chaquopy assets as well as APK `lib/` entries. Default script tests skip the adapter contract tests if host NumPy is absent; real model inference remains a separate explicit check.

Current test limitations: no working Android device/emulator is available on this host. Host inference, compile/unit tests, lint and APK inspection cannot establish successful Android startup, playback, UI behavior, memory use or thermal performance.
