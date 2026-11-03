# Contributing

RunLocal application source is licensed under Apache-2.0 (see LICENSE and NOTICE). Contributions are made under that license. Dependency and model licenses remain separate.

Read AGENTS.md for architecture and implementation invariants, and RELEASING.md for releases.

Use the pinned native dependencies and build commands in README.md. Keep the Chat/Models structure, run inference locally, and do not add analytics, accounts, prompt uploads, or network inference as a fallback. Model setup network requests must remain user initiated. Disclosed, disableable release checks may read public GitHub metadata only.

New runtime/model support needs explicit architecture checks, bounded file parsing, storage/RAM handling, and a clear unsupported-model error. Model discovery must preserve originals and Android storage boundaries. Retain immutable download revisions, publisher-hash verification, pause/resume, and cached-file reuse.

For changes, run the relevant unit tests, `:app:assembleDebug`, and `:app:lintDebug`. Conversation changes need isolation/restart/failure coverage. Record physical-device checks separately from host tests; never infer successful phone generation from compilation or a container check. Follow V1-READINESS.md for release acceptance.

Never commit signing keys, credentials, local.properties, model weights, private chats, generated images, or build outputs. Distribute APKs through release artifacts after running scripts/package_apk.py. Do not change the app ID or break persisted data without an explicit migration.
