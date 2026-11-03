# RunLocal agent guide

Read README.md, CHANGELOG.md and V1-READINESS.md before changing release behavior. This is a native Android app, not a web app. Keep exactly Chat and Models tabs; composer tools belong inside the single message bar. Do the requested work, keep changes scoped, and report what was actually tested.

## Stack and architecture

- Kotlin 2.3.20, R8 9.1.31 override (LiteRT dependency Kotlin 2.4 metadata), Android Gradle Plugin 8.13.2, Gradle wrapper 8.13; Java 21 build/test runtime, Java 17 bytecode target. Android 12/API 31 minimum, target/compile API 36. Native Android Views; no Compose/React Native migration is implied.
- LiteRT-LM Android 0.18.1 for text; CPU or GPU selected per model. JNI stable-diffusion.cpp + GGML for images, CPU-only. Exact native revisions: native-deps.lock; run scripts/bootstrap-native.sh. NDK 28.2.13676358, CMake 3.22.1.
- MainActivity: view construction/lifecycle, chat coordination, offline Android speech. LocalEngine/LiteRtTextSession: serial native lifecycle and typed model conversations. ChatRepository: atomic per-chat files and idempotent reply completion. ModelStore/ImageStore: private model copies and persisted setup/job state. PhoneModelLibrary/Scanner: SAF grants, bounded discovery, preserved originals. Download/image foreground services run in separate processes.
- ReleaseFeed: strict stable GitHub release parsing. UpdateNotifications: optional daily JobScheduler checks and notification. Never send chats, prompts, images, model files or identifiers to an update endpoint.

## Rules learned during development

- Preserve app ID app.pocket.local, persisted enum names/JSON keys, source model files, history and signing continuity. Migrate data deliberately.
- Do not delete sound non-null types. Remove unnecessary `!!`; use local bindings, safe calls or explicit meaningful preconditions. Avoid blanket suppression, catch-and-ignore bugs and unrelated refactors.
- Run all text engine open/send/close operations on the same worker. Engine state belongs to one chat/model/backend; LFM3 convolution state can survive Conversation recreation. Never reuse the same Engine for another chat. Errors invalidate the session. No silent GPU-to-CPU or cloud fallback.
- File extensions/container validation do not establish model operator/device compatibility. A LoRA requires the matching base. Cactus private downloads are inaccessible through another app's grants; no storage sandbox bypass. Do not claim arbitrary Hugging Face models or video work.
- Model setup pins revisions and verifies publisher hashes. Read cross-process atomically replaced base files directly; AtomicFile.openRead can disturb another writer. Main process applies SharedPreferences selections; avoid cross-process prefs caching.
- No model weights, secrets, signing keys, build caches, private chats or generated images in source control. Put APKs in release assets, not git. Inspect staged files before any publication.
- Text speed/token counts must come from native benchmark data, never word/character/chunk estimates. Keep model load, runtime TTFT and first-text arrival distinct. Streaming callbacks update only their originating chat; native lifecycle remains serial.
- Preserve explicit processor choice and offline speech; no network recognition fallback. Automatic update checks are disclosed, can be disabled, and only read public release metadata.

## Validation and releases

`website/` is the static GitHub Pages landing page: plain HTML/CSS/JS, no build step, no analytics, no external scripts; keep its claims consistent with README.md and the current candidate status. `scripts/check_website.py` validates it.

Use `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug -Prunlocal.abis=arm64-v8a --max-workers=2` and `python3 -m unittest discover -s scripts -p 'test_*.py'`. Run tests meaningful to the change; no need to download multi-GB models for UI-only changes. Default tests skip optional live/native inference checks.

Never use `-Pandroid.injected.build.abi` for shared APKs: it can mark APKs testOnly and cause normal installation to fail. Package normal outputs with scripts/package_apk.py, supplying --previous for signer continuity. Verify standalone/non-test-only ARM64 and 16 KB alignment. A compile/unit-test pass does not verify phone UI or inference. Report missing emulator/KVM/device testing candidly.

For a version: increase versionCode, update versionName and CHANGELOG, build/check, and follow RELEASING.md. GitHub workflow builds signed minified release APKs and creates a draft, not an automatically published release. Review before publishing. Do not invent a repository, license, signature, test result or release. Do not commit a machine-specific debug key; distribution secrets belong in protected release settings. No stable v1 claims until device acceptance is recorded.
