# Changelog

Versions are ordered newest first. Earlier entries reconstruct the local development artifacts and notes; they are not claims of prior public GitHub releases. Candidate versions remain experimental until device acceptance.

## [Unreleased]

## [1.0.0-rc14] - 2026-11-27

### Added

- Download **Qwen3 0.6B Thinking** from the Chat catalog. Open **Show reasoning** on a reply to read the model's thinking as it streams or after the reply is saved.

### Known limits

- The experimental 347 MB Qwen3 thinking export is best for short questions. Its reasoning and answers can be wrong. On-device acceptance is pending.

## [1.0.0-rc13] - 2026-11-27

### Added

- **Download and install** downloads the verified update directly in RunLocal with visible progress. Android installs it automatically where allowed and opens its confirmation prompt when required.
- Update notifications open RunLocal. Installing rc13 from an earlier version requires one final APK download through the existing update link.

## [1.0.0-rc12] - 2026-11-27

### Added

- Choose installed OCR and transcription models from Chat’s model picker. The composer switches to image and camera controls for OCR, or WAV and microphone controls for transcription. Results appear in the conversation and can be copied.

### Known limits

- Audio imports accept mono 16-bit PCM WAV files up to two minutes. Physical-device testing of the picker, camera, microphone, and offline recognition is pending.

## [1.0.0-rc11] - 2026-11-27

### Added

- In Chat’s **＋** menu, choose **Add an image**, **Take a photo**, **Add an audio file**, or **Record audio**. Review, copy, or insert the extracted text into the chat draft.
- Chat links to the matching model catalog when a compatible OCR or transcription model needs to be installed.

### Known limits

- Audio imports accept mono 16-bit PCM WAV files up to two minutes. Recordings have the same duration limit. Camera, microphone, and in-place upgrade behavior still need physical-device testing.

## [1.0.0-rc10] - 2026-11-25

### Added

- A task-based model catalog for chat, OCR, speech and image generation, with direct downloads, device fit estimates and measured local timings.
- Model catalog edits published on GitHub appear independently of APK releases. The app keeps a valid offline copy, and installed models and downloads survive listing removals.
- Catalog recipes can pin multiple files and speaker IDs. Installed recipes retain their identity and metadata when GitHub listings change.

### Bundled adapter support

- **Chat** supports compatible `.litertlm` exports through LiteRT-LM and adds CPU llama.cpp support for complete instruct GGUF files. The catalog includes Qwen3, LFM3, and Qwen2.5 GGUF.
- **Image generation** supports Stable Diffusion 1.x and adds SDXL Base for text-to-image and image-to-image on CPU. The experimental MOSS Image SFT setup remains available.
- **Text-to-speech** supports Underdog Woof and Android offline voices. Rc10 adds Kitten TTS 2 Pico English, BlueV3 Noa, Kokoro INT8, and a Piper adapter for compatible recipes.
- **Transcription** supports Android offline microphone dictation and adds downloadable Whisper Tiny English and Zipformer Small English for short mono PCM WAV files.
- **OCR** supports Cactus Mirage for printed Latin-script text on ARM64 and adds Tesseract LSTM packs for English and Hindi.

### Known limits

- The new adapters are experimental. Physical-device acceptance and SDXL generation tests are pending. Piper is bundled and needs a catalog listing with a pinned, compatible pronunciation lexicon.

## [1.0.0-rc9] - 2026-11-19

### Added
- Experimental offline OCR for printed Latin-script text using Cactus Mirage on Android ARM64. Install the pinned model from **Models → Add model** or extract text from an image in Chat’s message tools. Review, copy, or insert the result into the draft.
- RunLocal landing page, support guide, and `llms.txt`, published through GitHub Pages and Vercel.

## [1.0.0-rc8] - 2026-11-10

### Fixed
- Offer published GitHub release candidates through an explicit **Include release candidates (experimental)** setting in App updates. Stable-only checks remain the default; development candidates stay clearly labeled and drafts are excluded.
- Select the highest validated version code from the most recent 30 releases when candidate updates are enabled, retaining same-repository release/APK validation and the metadata size limit. Clear cached availability on channel changes and reject an in-flight result from the previous channel.
- Rc7 and older check only GitHub's stable-release endpoint. Install rc8 once from GitHub, then enable candidate updates to receive future candidate notices. Updates still require user-approved APK installation; chat/model data is never sent.

## [1.0.0-rc7] - 2026-11-09

### Changed
- Keep exactly Chat and Models, with one installed-model library for text, voice and image models. Type filters, a single Add model panel and per-model menus replace the stacked setup/test screens. Saved-folder discovery and image component management open on demand.
- Chat's model picker now includes installed voice models. Type and send text to speak with Juno or Mira; saved voice conversations retain the exact model/voice and offer replay. Dictation stays in the shared composer; text replies retain read-aloud controls.
- Simplify chat headers, message actions and navigation; move processor options into message tools while retaining the explicit selected processor in the header. Keep one visible Woof credit link in the composer rather than repeating it on every reply.

### Fixed
- Release native text-model memory on its serial worker before downloaded speech starts. Fence delayed speech startup against cancellation; complete or fail voice replies only in their originating conversation.
- Preserve chat scroll position and editing focus during same-chat refreshes. Old chat/model keys and downloaded files remain intact; voice conversations add a new model-key prefix without changing existing JSON fields.

## [1.0.0-rc6] - 2026-11-08

### Added
- Extensible downloaded speech-model catalogue, pinned/hash-verified resumable setup, per-model voice selection, model removal and editable voice tests inside Models. Composer tools select a voice or read the draft aloud; replies use the chosen voice.
- First speech runtime adapter: English Underdog Woof (Juno and Mira), using its pinned reference implementation with Chaquopy/Python 3.13 and NumPy in a separate serial speech process. Weights download separately. Android inference remains experimental pending phone acceptance.
- Cooperative inference cancellation, foreground-only playback, audio focus, temporary-audio cleanup and measured model-load/generation/audio durations. Android offline voices and on-device dictation remain available.
- Required Woof attribution/non-commercial license and native Python dependency notices. The Woof runtime is separately licensed; arbitrary speech models require additional compatible adapters.
- APK alignment verification now covers native libraries extracted from Python asset archives as well as lib/. Builds use standalone ABI-filtered outputs compatible with Chaquopy, preserving the phone APK filename.

## [1.0.0-rc5] - 2026-11-03

### Fixed
- Declare ACCESS_NETWORK_STATE for the optional network-constrained update job. Android 14+ otherwise throws an uncaught SecurityException from startup scheduling, before a model is loaded.
- APK packaging now rejects builds missing that permission, with regression coverage for the rc4 permission set.

## [1.0.0-rc4] - 2026-11-03

### Added
- Branded Android launch splash, loading indicators and elapsed generation stages.
- Streaming text replies with native decode tokens/sec, runtime time to first token, output-token count and total/load/first-text timing.
- Per-reply performance details saved with chat history; missing native metrics are labeled unavailable instead of estimated.

### Fixed
- Native bootstrap no longer mistakes a fresh no-checkout clone for user modifications; existing changes remain protected.
- CI and release workflows use Java 21, matching the LiteRT dependency’s host-test bytecode requirements while retaining the Java 17 Android target.
- CI Android setup installs platform-tools without requesting the removed SDK tools package.
- Release signing path is resolved on the runner, instead of using an unsupported job-level context.
- Workflow validation now checks GitHub expressions with actionlint.

## [1.0.0-rc3] - 2026-11-03

### Added
- GitHub stable-release checks, optional daily background notifications, manual checks and a disable control. Official release builds embed their GitHub repository; installation remains user approved.
- CI checks, a signed/minified release build workflow creating reviewable GitHub drafts, signing identity checks and machine-readable version markers.
- Agent architecture/workflow instructions, this changelog and release instructions.

### Changed
- A single composer contains tools, message input, microphone and send/cancel. Image attachments/settings move into the tools menu.
- Correct offline dictation routing to the current chat composer and preserve results if the chat changes.
- Remove obsolete Studio/Voice draft and unused text-import UI paths.
- Remove forced null assertions and redundant null handling; retain useful non-null types.
- Pin a Kotlin-2.4-metadata-compatible R8 shrinker for the LiteRT dependency.
- Apache-2.0 license and 07rjain/runlocal release endpoint configured.
- BuildConfig supplies version labels; no hardcoded About version.

## [1.0.0-rc2] - 2026-11-03

### Fixed
- Upgrade LiteRT-LM 0.8.0 to 0.18.1 for the reported LFM3 export/runtime mismatch; Kotlin compiler upgraded to 2.3.20.
- Prevent native model state from carrying across chats; preserve a continuous native conversation within the owning chat, bound replay, and discard failed sessions.
- Check available memory when loading a model, not against already-loaded weights each turn.

### Added
- Persistent per-model CPU/GPU selection and required optional Android GPU library declarations.
- Separate model compatibility, memory, context and GPU error explanations; updated runtime notices and regression tests.

## [1.0.0-rc1] - 2026-11-03

- Replace four main tabs with Chat and Models, model selection, history drawer and separate saved conversations/drafts.
- Move image replies/export into Chat and setup into Models; preserve exact image component/adapter assignments in conversations.
- Automatically download/import supported Hugging Face text/image recipes; discover saved accessible local folders and select supported local candidates.
- Add source-release preparation and device acceptance documentation.

## [0.3.1] - 2026-11-02

- Add bounded local model discovery, remembered SAF folders/files, compatibility labels and imported-copy reuse.
- Recognize accessible Cactus model bundles without claiming runtime support or private-storage access.

## [0.3.0] - 2026-11-02

- Add automatic Hugging Face image/LoRA dependency setup, verified resumable downloads and trigger handling.

## [0.2.2] - 2026-11-02

- Correct standalone APK packaging to avoid Android testOnly installation failures; verify signing and 16 KB alignment before distribution.

## Earlier prototypes

- Native RunLocal identity, LiteRT text chat, Android offline speech and CPU diffusion integration. No detailed version history is asserted for unrecorded prototypes.
