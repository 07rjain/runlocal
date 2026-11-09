# Text and speech validation — 2026-11-09

RunLocal **1.0.0-rc6**, versionCode **12**. This follow-up tests the current candidate without changing application code or rebuilding the APK. It does **not** establish that all three features work on an Android phone.

| Feature | Observed result | Remaining acceptance |
| --- | --- | --- |
| Text-to-text, Qwen3 0.6B | Real Linux CPU inference passed a two-turn arithmetic check with networking blocked. | Android model loading, streaming, history, CPU/GPU and lifecycle. |
| Text-to-text, LFM3 1.7B | Loads and decodes, but failed the arithmetic check with both greedy and embedded-default sampling. Two other fresh-engine questions answered correctly. | Resolve the failed correctness check and test the exact export on the phone. |
| Text-to-speech, Woof Juno and Mira | Both generated valid nonzero PCM WAV audio through the current adapter with networking blocked. | Android Python/NumPy execution, audible speech quality, playback, audio focus and lifecycle. |
| Speech-to-text | Android integration reviewed; no live recognition test was possible. | Installed on-device recognizer, offline language pack, microphone permission and real speech in airplane mode. |

## Automated checks

Fresh run:

```sh
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest \
  -Prunlocal.abis=arm64-v8a \
  -Prunlocal.buildPython=/tmp/runlocal-python313/python/bin/python3.13 \
  --max-workers=2 --rerun-tasks
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 \
  python3 -m unittest discover -s scripts -p 'test_*.py'
```

- Debug and release: **84 tests each, 79 passed, 5 skipped, zero failures/errors**. Optional native/live checks were skipped. LocalEngine unit tests use fake sessions; they do not prove native model inference.
- Script suite: **16 passed**.
- `git diff --check`: passed.
- Logs: `dist/feature-check-unit-tests.log` and `dist/feature-check-scripts.log`.

Assembly, lint, APK structure/alignment/signing results are historical rc6 checks in [VALIDATION-rc6.txt](VALIDATION-rc6.txt); those build tasks were not repeated for this testing-only follow-up.

## Real text inference

Host: Linux x86_64, Python 3.13, `litert-lm-api==0.18.1`, NumPy 2.2.6. CPU with four threads; thinking disabled; requested context 2,048 and output cap 512 tokens. Engine/conversation creation, send and close ran serially; each independent test conversation owned a fresh Engine. Socket/DNS calls were blocked during inference. These are host results, not Android benchmarks or proof that arbitrary models work.

### Qwen3

- Repository: `litert-community/Qwen3-0.6B-int4`
- Pinned revision: `56621f0115eba5781051ef9714c06e16e6d40c0b`
- File: `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm`
- Size: 347,251,840 bytes
- Verified SHA-256: `2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139`
- Sampler: temperature 0, seed 0. This explicit override differs from the app's embedded-default sampler.

In one continuous conversation:

1. “What is 2 plus 2? Reply with only the number.” → “2 + 2 = 4. So the answer is 4.”
2. “Add one to that number. Reply with only the number.” → “4 + 1 = 5. So the answer is 5.”

Both answers were numerically correct, although they did not follow the requested number-only format. The runtime clamped the requested context to the model's 1,280-token limit. Native benchmark information was returned; no token counts were estimated from text.

Evidence: `dist/feature-validation/text-results.json`, `dist/feature-text-inference.log`.

### LFM3 — correctness failure remains

- Repository: `litert-community/LFM3-1.7B-LiteRT`
- Pinned revision: `a682915fd764e6c31ee9f34c3949cc94ff0f74a3`
- File: `LFM3-1.7B_mixed_int4_ekv2048.litertlm`
- Size: 1,441,620,928 bytes
- Verified SHA-256: `0400d8ffdb00e4bbe1209120e2c7baf05322a8c900839b0a94cc28f599011196`

Initial temperature-0/seed-0 run loaded successfully but answered “2” to “What is 2 plus 2? Reply with only the number.” The expected-answer assertion failed. Evidence retained: `dist/feature-validation/lfm3-results.json`, `dist/feature-lfm3-inference.log`.

A follow-up used the embedded sampler, matching the app's sampler policy, and the existing XNNPACK disk cache, matching its use of a persistent cache:

| Conversation | Prompt | Actual answer |
| --- | --- | --- |
| Fresh engine A, turn 1 | What is 2 plus 2? Reply with only the number. | `2` — incorrect |
| Same engine A, turn 2 | Add one to that number. Reply with only the number. | `3</think>3` — follows its earlier wrong number; does not reach the expected 5 |
| Fresh engine B | What is the capital of Japan? Answer briefly. | `The capital of Japan is Tokyo.` — correct |
| Fresh engine C | What is 7 times 8? Answer briefly. | `7 times 8 is 56. So, the answer is 56.</think>7 times 8 is 56. So, the answer is 56.` — numerically correct, with unwanted thought markup/repetition |

Evidence: `dist/feature-validation/lfm3-defaults-cached-results.json`, `dist/feature-lfm3-defaults-cached-inference.log`. The harness's `containsExpected` checks are smoke checks, not an accuracy benchmark. The follow-up process completed normally; its JSON status is `failed` because the expected-answer checks failed.

The pinned publisher card documents inconsistent thought markup with thinking disabled. That does not explain or excuse the incorrect arithmetic answer. No app bug or corrective app change has been established by these host runs; model/runtime/host behavior needs further diagnosis. Do not treat loading or the successful questions as an overall pass.

Two additional no-cache attempts did not complete: one was interrupted by the turn interruption, and the next was terminated after making no observable progress for over 20 minutes and consuming about 5.6 GB RSS. They produced no answer or complete results JSON. Logs are retained as `dist/feature-lfm3-defaults-interrupted.log` and `dist/feature-lfm3-defaults-inference.log`. No-cache results must not be described as passed.

## Real Woof synthesis

The current `app/src/main/python/runlocal_woof.py` adapter used pinned, hash-verified `ConwayResearch/Underdog-Woof` weights:

- Revision: `be702960539777fc3f698e54335276a8391b00f9`
- File: `woof_int8.npz`
- SHA-256: `d895fbbe84b8f4f461e3e94ab48b1164353b1988ce8d365f5aa8263d0de1203e`

Host Python 3.12.3 / NumPy 1.26.4 differs from the Android package's Python 3.13 / NumPy 1.26.2. Input: “Hello. This voice runs locally.” Socket/DNS calls were blocked. Both outputs were valid nonzero 24 kHz, mono, 16-bit PCM WAV:

- Juno: 77,760 frames; measured load 351 ms, generation 8,588 ms.
- Mira: 88,320 frames; measured load 242 ms, generation 19,617 ms.

These timings are host measurements. Valid audio structure does not prove intelligibility, subjective quality or Android playback. Evidence: `dist/feature-validation/tts-results.json`, `juno.wav`, `mira.wav` and `CREDIT.txt` in the same directory.

Voice by [Underdog Woof](https://underdog.ai). Its non-commercial license and attribution requirements apply; see [SPEECH-MODELS.md](SPEECH-MODELS.md).

## Android and speech-to-text blocker

Device discovery found no physical phone. `Pocket_API_35` could not boot; its suggested diagnostic command was tried once and reported “x86_64 emulation currently requires hardware acceleration” and “KVM requires a CPU that supports vmx or svm”. `/dev/kvm` is absent. No Android UI, microphone, speaker or in-app inference acceptance test ran.

Speech-to-text currently uses Android's installed **on-device SpeechRecognizer**, not a downloaded Woof or other bundled STT model. Source review confirms the availability check, `createOnDeviceSpeechRecognizer`, microphone permission handling, offline preference and explicit errors without online fallback. This is source review only; the phone's recognition service and language pack remain unverified.

To finish acceptance, connect an Android 12+ ARM64 phone with USB debugging enabled. Install/update the candidate while preserving app data, download the selected text model and Woof weights, and install an offline recognition language pack. In airplane mode, verify a multi-turn chat, both Woof voices, then dictate a known phrase into the composer and send it. Also verify read-aloud of the reply, cancellation, restart and chat isolation. Follow [the speech phone guide](SPEECH-MODELS.md) and [the device checklist](../V1-READINESS.md).

## Artifact identity

The existing APK is unchanged: `dist/runlocal-1.0.0-rc6-arm64.apk`.

- SHA-256 rechecked: `808e5fbb0849a087e8627a459b062b11744cbcb402ce247b03d423b842e01203`
- No application source changes, version bump, source commit or release publication in this follow-up.
- Logs/audio/results are local ignored `dist/` artifacts; model weights remain outside the repository.
