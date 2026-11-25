"""BlueV3 English preset synthesis with the pinned CFG graph layout.

Based on BlueTTS's MIT-licensed blue_onnx pipeline, commit
5a9d9a04df559499b9c12715ebe1780b0f7d588c. See assets/licenses.
"""
import json
from pathlib import Path
import re
import unicodedata
import wave

import numpy as np

SAMPLE_RATE = 44100
STEPS = 8
PHONEMES = {"t͡s": "ʦ", "t͡ʃ": "ʧ", "tʃ": "ʧ", "d͡ʒ": "ʤ", "dʒ": "ʤ", "d͡z": "ʣ", "dz": "ʣ"}


def _parts(text, pronounce):
    text = re.sub(r"\[[^\]]*\]", "", text)
    text = re.sub(r"(?<=[a-z])(?=[A-Z]{2,}(?![A-Za-z]))", " ", text)
    for sentence in re.split(r"(?<=[.!?])\s+", text):
        pending = ""
        for word in sentence.split():
            phrase = f"{pending} {word}".strip()
            if len(phrase) > 160 and pending:
                yield _ipa(pronounce(pending))
                pending = word
            else:
                pending = phrase
        if pending:
            yield _ipa(pronounce(pending))


def _ipa(value):
    value = unicodedata.normalize("NFD", value).strip()
    for original, replacement in PHONEMES.items():
        value = value.replace(original, replacement)
    value = re.sub(r"ts(?=[^\w]|$)", "ʦ", value)
    value = value.translate(str.maketrans({"“": '"', "”": '"', "‘": "'", "’": "'", "–": "-", "—": "-"}))
    return value if not value or value[-1] in ".,!?:;'\"" else value + "."


def synthesize(directory, text, output, pronounce, infer, check):
    root = Path(directory)
    vocab = json.loads((root / "vocab.json").read_text(encoding="utf-8"))["char_to_id"]
    voice = json.loads((root / "voice.json").read_text(encoding="utf-8"))
    style = np.array(voice["style_ttl"]["data"], dtype=np.float32).reshape((1, 50, 256))
    style_dp = np.array(voice["style_dp"]["data"], dtype=np.float32).reshape((1, 8, 16))
    speed = float(voice.get("speed_scale", {}).get("en", 1.0))
    if not 0.25 <= speed <= 4:
        raise ValueError("Invalid BlueV3 voice speed.")
    frames = 0
    rng = np.random.default_rng(0)
    with wave.open(output, "wb") as wav:
        wav.setparams((1, 2, SAMPLE_RATE, 0, "NONE", "not compressed"))
        for ipa in _parts(text, pronounce):
            check()
            if not ipa:
                continue
            tagged = f"<en>{ipa}</en>"
            if len(tagged) > 300:
                raise ValueError("Use shorter sentences for BlueV3.")
            ids = np.array([[vocab.get(c, 0) for c in tagged]], dtype=np.int64)
            mask = np.ones((1, 1, ids.shape[1]), dtype=np.float32)
            duration = float(infer(0, {"text_ids": ids, "text_mask": mask, "style_dp": style_dp}).item()) / speed
            if not 0 < duration <= 30:
                raise ValueError("BlueV3 returned an invalid speech duration.")
            length = max(1, int(np.ceil(duration * SAMPLE_RATE / 3072)))
            latent = rng.standard_normal((1, 144, length), dtype=np.float32)
            embedding = infer(1, {"text_ids": ids, "text_mask": mask, "style_ttl": style})
            latent_mask = np.ones((1, 1, length), dtype=np.float32)
            for step in range(STEPS):
                check()
                latent = infer(2, {"noisy_latent": latent, "text_emb": embedding, "style_ttl": style,
                                   "latent_mask": latent_mask, "text_mask": mask,
                                   "current_step": np.array([step], np.float32),
                                   "total_step": np.array([STEPS], np.float32),
                                   "cfg_scale": np.array([4.0], np.float32)})
            audio = infer(3, {"latent": latent}).reshape(-1)
            if not len(audio) or not np.isfinite(audio).all():
                raise ValueError("BlueV3 returned invalid audio.")
            if frames:
                silence = SAMPLE_RATE // 10
                wav.writeframesraw(bytes(2 * silence))
                frames += silence
            peak = max(0.95, float(np.max(np.abs(audio))))
            wav.writeframesraw((audio * (0.95 * 32767 / peak)).round().astype("<i2").tobytes())
            frames += len(audio)
        check()
        if not frames:
            raise ValueError("No text to speak.")
    return frames


def android_synthesize(directory, text, output, pronunciation, graphs, cancellation):
    from java import jarray, jbyte, jclass

    input_type = jclass("app.pocket.local.BlueInput")

    def infer(index, arguments):
        inputs = []
        for name, value in arguments.items():
            longs = value.dtype == np.int64
            payload = np.ascontiguousarray(value, dtype="<i8" if longs else "<f4").view(np.int8)
            inputs.append(input_type(name, list(value.shape), jarray(jbyte)(payload.reshape(-1).tolist()), longs))
        result = graphs[index].infer(jarray(input_type)(inputs))
        payload = np.asarray(list(result.getBytes()), dtype=np.int8)
        return payload.view("<f4").reshape(tuple(result.getShape()))

    def check():
        if cancellation.isCancelled():
            raise InterruptedError("Speech cancelled.")

    return synthesize(directory, text, output, pronunciation.pronounce, infer, check)
