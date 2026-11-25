# Third-party files and licences

Every third-party file in this bundle, and the third-party components the front end needs at run time.

## Shipped in this bundle

| file(s) | component | licence | attribution / notes |
|---|---|---|---|
| `frontend/polyg2p-0.1.0-cp39-abi3-manylinux_2_34_x86_64.whl` | polyg2p (Rust G2P with Python bindings) | Apache-2.0 | Copyright 2026 Maxim Melichov and contributors. Full text: `frontend/polyg2p_data/LICENSE`; notices: `frontend/polyg2p_data/NOTICE`. Contains no code or data from eSpeak NG. The Italian stress / vowel-openness tables compiled into it are aggregate statistics computed from WikiPron (Wiktionary, CC BY-SA); see NOTICE. |
| `frontend/polyg2p_data/en.lex`, `en.g2p`, `en-large.g2p` (also in `polyg2p_data_nolex/`) | English lexicon and out-of-vocabulary models | derived from the CMU Pronouncing Dictionary (BSD-style) | "Copyright (C) 1993-2015 Carnegie Mellon University. All rights reserved." -- the full CMU notice is reproduced in `frontend/polyg2p_data/NOTICE`, as its licence requires. |
| `frontend/polyg2p_data/de.lex` | German lexicon | **CC BY-SA** (Wiktionary-derived) | From german-ipa-dict (devio-at) via ipa-dict, from Wiktionary (https://www.wiktionary.org), contributors of Wiktionary, CC BY-SA 3.0 / 4.0. Distributed under the same share-alike terms; changes: converted to the polyg2p lexicon format and IPA conventions. |
| `frontend/polyg2p_data/it.lex` | Italian lexicon | **CC BY-SA** (Wiktionary-derived) | From WikiPron (Lee et al., 2020), extracted from Wiktionary (https://www.wiktionary.org), contributors of Wiktionary, CC BY-SA 3.0 / 4.0. Distributed under the same share-alike terms; changes: converted to the polyg2p lexicon format and IPA conventions. |
| `frontend/polyg2p_data_nolex/` | the same data without `de.lex` / `it.lex` | as above (no BY-SA files) | Select with `BLUE_G2P_NOLEX=1` (German and Italian then run on polyg2p's rules); deleting `polyg2p_data/de.lex` and `polyg2p_data/it.lex` needs no rebuild. |
| `frontend/renikud/model.onnx` | RenikudPlus Hebrew G2P weights (Hugging Face `notmax123/RenikudPlus` @679c56c) | Apache-2.0 | Full text: `frontend/renikud/LICENSE`; provenance and sha256: `frontend/renikud/README.md`. Not a non-commercial licence. |
| `voices/*.json` (15 voices) | style tokens computed from reference recordings of LibriTTS-R speakers: maya 6135, noa 1088, lily 6147, adam 8088, daniel 6209, tamar 3513, shira 6548, yael 6104, emma 1050, sara 6388, david 708, jonathan 8419, ethan 340, michael 7228, ariel 6954 | CC BY 4.0 (LibriTTS-R corpus) | LibriTTS-R: Y. Koizumi et al., "LibriTTS-R: A Restored Multi-Speaker Text-to-Speech Corpus", Interspeech 2023; derived from LibriTTS (H. Zen et al., 2019) and LibriVox public-domain audiobooks. The JSONs hold encoder outputs, not audio. |
| `onnx/vocoder.onnx`, `onnx_plain/vocoder.onnx`, `engine*/decoder*.onnx` | audio decoder fine-tuned from the Supertonic-3 vocoder (Supertone Inc., [Supertone/supertonic-3](https://huggingface.co/Supertone/supertonic-3)) | BigScience OpenRAIL-M | Derivative weights: the OpenRAIL-M use-based restrictions apply to them; full text in `LICENSE.OpenRAIL-M`. |
| `frontend/polyg2p_data*/ru_accents.lex`, `ru_yo.lex`, `ru_yo_homographs.lex` | Russian stress / ё dictionaries (polyg2p) | MIT | derived from the RUAccent dictionaries; copyright RUAccent authors (see `frontend/polyg2p_data/NOTICE`) |
| `frontend/yi/` | Yiddish front end (Phonikud-yi G2P + ReNikud-yi context model) | MIT | see `frontend/yi/NOTICE` |
| `frontend/yi/data/lexicons/sefaria_pointed_lk.py`, `homograph_lk.py` | Yiddish/Hebrew-script readings | CC BY-SA 4.0 | derived from pointed editions published on Sefaria ("Miqra according to the Masorah", CC BY-SA 4.0; "Torat Emet 357", Public Domain) |
| `bluetts/blue-release.patch` | change to BlueTTS (`blue_onnx`) | MIT (as BlueTTS) | The patched attention used in `onnx/reference_encoder.onnx` export is adapted from BlueTTS `exports/export_onnx.py` (MIT). |

## Needed at run time, not shipped

| component | used for | licence | how to get it |
|---|---|---|---|
| `renikud-plus` 0.5.0 | runs the RenikudPlus weights (Hebrew G2P) | MIT | `pip install "renikud-plus[cpu]==0.5.0"` (Python >= 3.12) -- Maxim Melichov, Yakov Kolani, Morris Alper |
| `heb-tts-normalizer` 0.2.0 | Hebrew text normalisation (engine front end) | MIT | `pip install heb-tts-normalizer==0.2.0` -- thewh1teagle, https://github.com/thewh1teagle/heb-tts-normalizer |
| `hebrew-num2words` 0.1.0 | Hebrew numbers to words (engine front end) | Apache-2.0 | `pip install hebrew-num2words==0.1.0` -- Max Melichov |
| ONNX Runtime | inference | MIT | `pip install onnxruntime` |
