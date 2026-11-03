# Version 1 readiness

The current deliverable is **1.0.0-rc4**, a development candidate for review. The selected public repository is https://github.com/07rjain/runlocal; source is published there, while an app release remains pending.

## Implemented for this candidate

- [x] Exactly Chat and Models in bottom navigation.
- [x] Model selector, history drawer, new/switch/delete conversations.
- [x] Continuous text chat, persisted drafts/history, migration of previous chat history.
- [x] Text/image replies associated with their originating conversation.
- [x] Image output and export within Chat; setup only in Models.
- [x] Automatic supported Hugging Face text/image setup from one link, with pause/resume and integrity/storage/RAM checks.
- [x] Existing app models, automatic saved-folder scans, persisted-grant adoption, automatic compatible-model choice, and imported-copy reuse.
- [x] Clear limitations for private Cactus storage and unsupported formats.

- [x] LiteRT-LM 0.18.1 API/compiler upgrade and per-model CPU/GPU selection; separate native engine ownership for each chat/backend.

- [x] Changelog, agent guide, signed-release/draft workflow, release metadata and update checks; consolidated composer and null-assertion cleanup.

## Physical-device acceptance before declaring v1 stable

- [ ] Install/update this standalone APK over 0.3.1 on the Motorola Edge 60 Pro; verify models and old history survive.
- [ ] With a small CPU LiteRT-LM model, send several turns in airplane mode, switch chats during generation, restart, and verify reply/draft/history isolation.
- [ ] Run the exact LFM3 mixed-int4/2048 export on CPU and GPU; restart the app to confirm processor selection, verify a GPU failure remains an explicit failure, and check separate chats do not share LFM3 state.
- [ ] Connect a folder once, restart, and verify automatic discovery/selection without another picker. Check moved files and revoked permissions.
- [ ] Add a supported public text model link, pause/restart/resume, verify reuse, and run it in airplane mode. Reject unsupported/gated/device-specific links clearly.
- [ ] With a small SD 1.x checkpoint, generate/export an image in Chat and run image-to-image. Switch chats during generation and verify routing, then cancel/restart a job.
- [ ] Verify model file removal fails clearly in an older chat; no silent LoRA/base substitutions.
- [ ] Test offline microphone/read-aloud, denied permissions, keyboard/rotation, long chats, and accessibility on the phone.
- [ ] Verify live streaming, loading states, measured TTFT/token rates, saved metrics and chat switching during generation on CPU/GPU.
- [ ] Verify the single composer on a phone, notification permission/disable behavior, stable release detection and signed APK update continuity.
- [ ] Measure memory, speed, thermal behavior, and battery use. Keep MOSS experimental until full matching base+adapter generation succeeds on target hardware.

## Before public open-source release

- [x] Apache-2.0 selected; LICENSE and contributor attribution NOTICE added to source and APK assets.
- [ ] Review redistributed direct/transitive dependency notices against the packaged APK; retain their license terms.
- [ ] Choose a production signing/distribution policy. Preserve existing development signer continuity for local upgrades; never publish private keys.
- [x] Initial source contents reviewed for secrets, personal data, caches, weights, runtime dumps and unwanted binaries. Repeat this review for each release.
- [x] Repository selected: 07rjain/runlocal.
- [x] Publish the reviewed candidate source under Apache-2.0.
- [ ] Configure signing and review APK release contents before publication.
- [ ] Record physical-device acceptance results and resolve blockers, then promote the candidate to a stable v1 version/tag.

Video generation, custom speech-model import, GGUF text, Cactus execution, authenticated downloads, and unrestricted arbitrary model conversion remain future work. They are not silently included in v1 claims.
