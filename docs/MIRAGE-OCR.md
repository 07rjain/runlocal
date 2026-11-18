# Mirage OCR in RunLocal

This is an experimental feature in the unreleased Android ARM64 build. The published rc8 APK does not contain it.

1. Install the development APK on an Android 12+ ARM64 phone. The standalone test APK keeps the app ID, version code and signer used by the public rc8 APK.
2. Open **Models → Add model → Offline OCR → Cactus Mirage** and tap **Download Mirage**. You can also paste the public [Mirage repository URL](https://huggingface.co/Cactus-Compute/mirage). The download is 21.6 MB and can resume after interruption.
3. Go to **Chat → ＋ → Extract text from an image → Choose an image**. The model processes the selected image locally. Review the transcript, then copy it or insert it into the chat draft. Inserting text does not send a message.
4. Use **Models → OCR** to find the installed Mirage model. Its management view can remove RunLocal's copy without deleting chat drafts or source photos.

Mirage reads printed Latin-script text. Small text, complex layouts and accents can be wrong. RunLocal accepts still images under 24 MB, scales the long edge to at most 1,600 pixels, and requests text regions only. It groups detected fragments by line. Dense documents, columns, handwriting and other scripts have not been validated. Android x86_64 builds do not offer OCR.

## Runtime and verification

- The model is the 21,611,043-byte `mirage.cact` at HF revision `3d03cee88c0acbe4e71b94b567ab14e6e55f40c9`. Its publisher SHA-256 is `4249a0f7515272ff76b9414bec17d7e7daf84b15b66c7fb4f17fce1e66f9f237`. The APK bundles the pinned Apache-2.0 [Cactus C runtime 4.1.4](https://huggingface.co/Cactus-Compute/cactus/tree/f4ab2741203a282c6e7b89983de2b5e5b2e805a8/releases/4.1.4), with its license. Model weights download separately. OCR runs in a separate app process.
- The exact weights ran against the Linux build of Cactus 4.1.4 through RunLocal’s JNI and transcript formatter. The synthetic sample returned `RUNLOCAL OCR`, `TRAIN AT 09:45`, and `PLATFORM 7` in order. The blank image returned no text. Repeating the synthetic scan in the same process returned the same transcript. The [publisher’s stop-sign sample](https://cactuscompute.com/mirage/coco-231049.jpg) returned `ARRÉT` instead of `ARRÊT`, matching the published limitation.
- The app’s shared downloader resumed a 1 MiB partial file against the public Hugging Face artifact and verified the resulting SHA-256. A Linux syscall trace of inference showed no network connections. The OCR workflow does not contain an upload or cloud fallback.
- Android ARM64 debug assembly, lint, and unit tests passed. The standalone APK passed signing continuity against public rc8 and 16 KB ZIP and native library alignment. Those checks do not exercise Android image selection or inference.
- No compatible phone or usable Android emulator was available here. On a phone, verify download/resume, image selection, OCR in airplane mode, transcript review/insertion, cancellation, and model removal. If OCR is cancelled during a native read, the result is discarded when that read returns.
