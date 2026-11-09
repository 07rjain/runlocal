"""RunLocal's Android adapter for the pinned, separately licensed Underdog Woof runtime.

Only bundled code executes. Model downloads contain weights, not Python modules.
"""
import gc
import time
import wave

import numpy as np
from underdog_woof import Woof
from underdog_woof import runtime
from underdog_woof.textsplit import split_text


def synthesize(weights, voice, text, output, cancellation):
    def check():
        if cancellation.isCancelled():
            raise InterruptedError("Speech cancelled")

    runtime.CANCEL_CHECK = check
    tts = None
    try:
        check()
        start = time.monotonic()
        tts = Woof(weights=weights, voice=voice)
        loaded = time.monotonic()
        check()
        normalized = tts.norm(text)
        pieces = split_text(normalized)
        if not pieces:
            raise ValueError("No speakable English text was found.")
        frames = 0
        runtime.RNG.seed(0)
        with wave.open(output, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(24000)
            for piece in pieces:
                check()
                audio = tts._piece(piece)
                check()
                if not np.isfinite(audio).all() or not len(audio):
                    raise ValueError("The speech model returned invalid audio.")
                pcm = (np.clip(audio, -1, 1) * 32767).round().astype("<i2")
                wav.writeframes(pcm.tobytes())
                frames += len(audio)
        check()
        return [int((loaded - start) * 1000), int((time.monotonic() - loaded) * 1000), frames]
    finally:
        runtime.CANCEL_CHECK = lambda: None
        del tts
        gc.collect()
