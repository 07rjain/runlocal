# Releasing RunLocal

The release workflow creates a **draft** for review. The selected repository is https://github.com/07rjain/runlocal and the application license is Apache-2.0. The source is published in that repository; an app release has not yet been published. CI cannot establish actual phone inference; complete V1-READINESS.md before stable v1.

## Configure once

1. Put source in the chosen GitHub repository. Create a protected `release` environment for distribution secrets.
2. Configure environment secrets `RUNLOCAL_KEYSTORE_BASE64`, `RUNLOCAL_STORE_PASSWORD`, `RUNLOCAL_KEY_ALIAS`, `RUNLOCAL_KEY_PASSWORD`. Supply keys through a secret manager, never chat, committed files or logs. Base64 is only encoding, not encryption.
3. Set the environment variable `RUNLOCAL_CERTIFICATE_SHA256` to the expected signing certificate digest. The workflow fails on a mismatch. Keep encrypted/offline backups of your signing material.
4. Existing development APKs use certificate `4dc9269b18a7432cd749fe1a5bf73fb62cdcb126f501b3b84bdf598c75829e9f`. To update those installations in place, privately provision that same key for distribution. A new key cannot update them in place under the current signing scheme. Do not silently replace the signer or publish a private debug key. Forks should use their own identity/key.
5. Release builds embed `github.repository` automatically. Local builds can set `-Prunlocal.repository=owner/repository`; the default is 07rjain/runlocal; an explicit empty value disables update checks and clearly reports the missing configuration.

## Each version

1. Increase versionCode and set versionName in app/build.gradle.kts. Add the exact version to CHANGELOG.md. Never reuse version codes or publish a tag inconsistent with the APK version.
2. Run checks, inspect actual source/release contents, record device acceptance and confirm signing continuity. Preserve backups during migration testing.
3. Push a matching `v<versionName>` tag. The workflow runs unit tests/lint, builds a signed/minified release APK, verifies packaging/signature/alignment, checks the certificate, and creates a draft with APK/checksum/validation assets and changelog notes. Missing secrets or mismatch fail the job; no unsigned/debug fallback.
4. Review the draft and assets. Candidate tags containing a hyphen are marked prerelease. Publish only after review; do not call an untested candidate stable.
5. The release body must retain `<!-- runlocal-version-code: N -->`. scripts/release_notes.py adds it after verifying the tag and APK report. The Android app compares this integer with its installed versionCode and requires the matching ARM64 asset from the same repository. Editing away the marker/asset disables detection safely.

## Update behavior and limits

The app checks the GitHub `/releases/latest` endpoint for **published stable releases**. Drafts/prereleases do not trigger notifications. Enabled official builds check at most daily on foreground use and schedule a network-constrained periodic Android job. Android can delay jobs, and force-stopped apps do not run them. Manual checks are available under history → App updates. No prompts, files or device identifiers are uploaded; GitHub receives ordinary network request metadata.

Android 13+ needs notification permission; denied notifications do not prevent foreground update notices. The automatic toggle also cancels periodic work. Notifications open the validated GitHub release page. Users download the signed APK and approve Android installation; this app does not silently replace itself. The update mechanism cannot be retrofitted remotely into already-installed rc2/older binaries: those users first install a build containing it with the correct repository and signer.

## Local signed release build

Set the four RUNLOCAL signing environment variables above (KEYSTORE is a private file path locally), then:

```sh
./scripts/bootstrap-native.sh
./gradlew :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease \
  -Prunlocal.abis=arm64-v8a -Prunlocal.repository=owner/repository --max-workers=2
python3 scripts/package_apk.py app/build/outputs/apk/release/app-arm64-v8a-release.apk \
  --previous /path/to/previous.apk --output-dir dist
python3 scripts/release_notes.py --report dist/INSTALLATION-CHECKS.json \
  --tag v1.0.0-rc3 --output dist/RELEASE-NOTES.md
```

Never store passwords in Gradle properties committed to git. Without signing configuration, release builds are unsigned and the packaging verifier rejects them. Protect release environments and review workflow/dependency updates before running them with secrets.
