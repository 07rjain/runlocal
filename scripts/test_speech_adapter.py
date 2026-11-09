"""Adapter contract tests; optional NumPy dependency, no model download or inference required."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import wave

HAS_NUMPY = importlib.util.find_spec('numpy') is not None
if HAS_NUMPY:
    import numpy as np
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/python'))
    import runlocal_woof as adapter


class Cancellation:
    def __init__(self): self.cancelled = False
    def isCancelled(self): return self.cancelled


@unittest.skipUnless(HAS_NUMPY, 'optional host NumPy is not installed')
class SpeechAdapterTest(unittest.TestCase):
    def test_cancel_before_load_does_not_open_weights(self):
        cancellation = Cancellation()
        cancellation.cancelled = True
        with patch.object(adapter, 'Woof') as model:
            with self.assertRaises(InterruptedError):
                adapter.synthesize('/missing/weights', 'juno', 'Hello.', '/unused.wav', cancellation)
            model.assert_not_called()
        adapter.runtime.check_cancel()  # Hook must be reset after failure.

    def test_writes_pcm_audio_and_checks_cancellation_between_pieces(self):
        cancellation = Cancellation()
        with tempfile.TemporaryDirectory() as root, patch.object(adapter, 'Woof') as model:
            model.return_value.norm.side_effect = lambda text: text
            model.return_value._piece.return_value = np.array([0.0, .5, -.5] * 100, dtype=np.float32)
            output = str(Path(root) / 'audio.wav')
            metrics = adapter.synthesize('/weights', 'juno', 'Hello.', output, cancellation)
            with wave.open(output) as wav:
                self.assertEqual((wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getnframes()), (1, 2, 24000, 300))
            self.assertEqual(metrics[2], 300)
            def cancel_during_piece(_):
                cancellation.cancelled = True
                return np.ones(100, dtype=np.float32)
            model.return_value._piece.side_effect = cancel_during_piece
            with self.assertRaises(InterruptedError):
                adapter.synthesize('/weights', 'mira', 'Hello.', output, cancellation)
        adapter.runtime.check_cancel()

    def test_invalid_audio_is_not_playable_output(self):
        with tempfile.TemporaryDirectory() as root, patch.object(adapter, 'Woof') as model:
            model.return_value.norm.side_effect = lambda text: text
            model.return_value._piece.return_value = np.array([float('nan')], dtype=np.float32)
            with self.assertRaisesRegex(ValueError, 'invalid audio'):
                adapter.synthesize('/weights', 'juno', 'Hello.', str(Path(root) / 'audio.wav'), Cancellation())
