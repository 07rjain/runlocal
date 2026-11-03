# Changelog

Versions are ordered newest first. Earlier entries reconstruct the local development artifacts and notes; they are not claims of prior public GitHub releases. Candidate versions remain experimental until device acceptance.

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
