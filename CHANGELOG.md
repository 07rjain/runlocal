# Changelog

Versions are ordered newest first. Earlier entries reconstruct the local development artifacts and notes; they are not claims of prior public GitHub releases. Candidate versions remain experimental until device acceptance.

## [Unreleased]

### Added
- Static landing page in `website/` with a GitHub Pages deployment workflow and a markup/link checker. No app changes.

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
