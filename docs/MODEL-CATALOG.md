# Maintain the RunLocal model catalog

Edit [model-catalog.json on GitHub](https://github.com/07rjain/runlocal/blob/main/app/src/main/assets/model-catalog.json). After the corresponding adapter is installed, catalog edits take effect independently of APK updates. Open **Models → Add model → Refresh catalog** to check for changes. A failed refresh leaves the last valid catalog available offline.

- Keep an entry's `id` stable when editing its title or description. Leave `schemaVersion` at `1`.
- Set `"enabled": false` to hide a listing temporarily, or delete its object from `models`. Installed recipes and conversations stay on the phone.
- Fixed built-in IDs include `mirage`, `underdog-woof-v1`, `kitten-2-pico-en-int4` and `android-dictation`. Restore one with `{"id":"mirage","runtime":"builtin"}`.

## Adapter inventory

| Runtime or adapter ID | Supported inputs |
| --- | --- |
| `litert-lm` | One generic compatible `.litertlm` chat export. Enable `gpu` only when the publisher documents it for that export. |
| `litert-qwen3-thinking-v1` | One compatible Qwen3 thinking `.litertlm` text export with a context of at least 1,280 tokens and Qwen3's ChatML and thinking delimiters. RunLocal supplies the template and routes the thought channel into Chat. |
| `llama-gguf-v1` | One complete instruct GGUF with a chat template supported by llama.cpp. CPU. Projection files, split weights, embeddings and multimodal models require different contracts. |
| `sd15` | One complete Stable Diffusion 1.x GGUF or Safetensors checkpoint. |
| `sdxl-v1` | One complete SDXL Base GGUF or Safetensors checkpoint including its text encoders and VAE. CPU, experimental. |
| `woof-v1` | `weights` NPZ compatible with Woof. Voice IDs `juno` and `mira`. Woof's non-commercial terms and attribution apply. |
| `kitten-pico-en-v1` | `model` English Pico ONNX, `voices` NPZ, `vocabulary` JSON, and `dictionary` CMU Misaki JSON. Voice IDs `eleanor`, `edith`, `otis`, `frank`. |
| `bluev3-en-v1` | `duration`, `encoder`, `flow`, `vocoder` BlueV3 ONNX graphs matching the release CFG layout, `voice` preset style JSON, `vocabulary` JSON, `lexicon` en.lex, `pronunciation` en-large.g2p. Set English and one preset voice. |
| `sherpa-kokoro-v1` | Kokoro `model` ONNX, `voices` BIN, `tokens` TXT and matching `lexicon` TXT. Use numeric speaker IDs. |
| `sherpa-piper-v1` | Compatible Piper/VITS `model` ONNX, `tokens` TXT and a matching sherpa-onnx 2.0 `lexicon` TXT. Use numeric speaker IDs. The lexicon must use the exact voice's phoneme inventory and the `phrase \|\| phonemes` format for Piper. A general Kokoro dictionary and legacy eSpeak files are incompatible. Rc10 includes the adapter and has no default Piper listing. |
| `sherpa-whisper-v1` | Matched Whisper `encoder` and `decoder` ONNX plus `tokens` TXT. Set the Whisper language code. |
| `sherpa-zipformer-v1` | Matched **offline transducer** `encoder`, `decoder` and `joiner` ONNX plus `tokens` TXT. |
| `tesseract-lstm-v1` | One Tesseract 4/5 LSTM `language` traineddata file. Set its matching language code, such as `eng` or `hin`. |

Whisper and Zipformer use **Chat → ＋ → Transcribe an audio file** for mono 16-bit PCM WAV files up to two minutes. Android dictation remains available from the microphone.

The Piper Lessac Medium export produced intelligible English in a Linux smoke test with sherpa-onnx 2.0.3 and its upstream `lexicon-en-us.txt`. A Piper catalog listing needs a public Hugging Face copy of the matching lexicon with verified redistribution terms and a pinned hash. [Lessac Medium export](https://huggingface.co/csukuangfj/vits-piper-en_US-lessac-medium/tree/83f7470750b36549037551ef7007fa3b4b8697e0) · [Upstream lexicon](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/lexicon-en-us.txt)

## Add a compatible model

1. Check the publisher's export, processor requirements, license, and file layout against the table. Copy a checked-in example for the same kind of recipe and give it a new lowercase `id`. Follow the adapter's exact role list.
2. For a single-file chat or image listing, generate the entry:

   ```sh
   python3 scripts/catalog_entry.py Qwen/Qwen2.5-0.5B-Instruct-GGUF qwen2.5-0.5b-instruct-q4_0.gguf \
     --runtime llama-gguf-v1 --id qwen25-q4 --title "Qwen2.5 0.5B GGUF" \
     --summary "Compact CPU chat model." --experimental
   ```

3. For a multi-file recipe, set `"runtime": "builtin"` and `"adapter"` to the versioned ID. Fill `"files"` with the required role names. Generate each file object with `python3 scripts/catalog_entry.py OWNER/REPO EXACT_PATH --revision TAG_OR_COMMIT --pin-only`. It prints the immutable revision, size and SHA-256. Include `"language"` where applicable. A TTS `"voices"` array contains objects with string `id`, `name` and `language` fields.
4. Run `./gradlew :app:testDebugUnitTest --tests app.pocket.local.ModelCatalogTest` or look for a passing Android check in the PR before merging. The validator rejects unsupported adapter IDs in the checked-in catalog, missing roles, duplicates, unsupported filenames and unsafe or unpinned metadata. Verify the actual graph and behavior by running the exact recipe on a phone. Keep the experimental flag until then.
5. Merge to `main`, refresh the catalog on the installed APK, download the new entry and try it offline. Devices without its adapter skip the newer listing. Keep downloaded recipes available for old chats even if a listing changes.

A new execution contract needs its own versioned adapter in an APK. A compatible recipe can update over GitHub.

For a Qwen3 thinking listing, generate its pinned fields as `litert-lm` in step 2, then set `"runtime": "builtin"` and `"adapter": "litert-qwen3-thinking-v1"`. Use the exact thinking export. Verify that a short prompt emits both reasoning and a final answer before publishing the listing. Older builds skip this adapter.
