import argparse
import hashlib
import json
import wave
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

import numpy as np
from numpy.typing import NDArray
from pydantic import JsonValue

from signal_lab_fixtures.contracts import validate
from signal_lab_fixtures.oracle import load_profile


def write_pcm(path: Path, samples: NDArray[np.int16], sample_rate: int) -> None:
    if samples.ndim != 2 or not 1 <= samples.shape[1] <= 4:
        raise ValueError("Expected frames by channels, with one to four channels")
    with wave.open(str(path), "wb") as recording:
        recording.setnchannels(samples.shape[1])
        recording.setsampwidth(2)
        recording.setframerate(sample_rate)
        recording.writeframes(samples.astype(np.dtype(np.int16).newbyteorder("<")).tobytes())


def write_fixture_catalog(directory: Path) -> dict[str, JsonValue]:
    profile = load_profile()
    size = profile.windowFrames
    quarter = np.tile(np.array([0, 8192, 0, -8192], dtype=np.int16), size // 4)
    dc = np.full(size, 8192, dtype=np.int16)
    stereo = np.column_stack((quarter, dc))
    stereo = np.vstack((stereo, np.array([[32767, -32768]] * 3, dtype=np.int16)))
    recipes = {
        "zero-mono": np.zeros((size, 1), dtype=np.int16),
        "dc-mono": dc.reshape(-1, 1),
        "nyquist-mono": np.tile(np.array([8192, -8192], dtype=np.int16), size // 2).reshape(-1, 1),
        "quarter-cycle-mono": quarter.reshape(-1, 1),
        "stereo-with-tail": stereo,
    }
    directory.mkdir(parents=True, exist_ok=True)
    records: list[JsonValue] = []
    for name, samples in recipes.items():
        identifier = str(uuid5(NAMESPACE_URL, f"urn:signal-lab:fixture:{name}:v1"))
        path = directory / f"{identifier}.wav"
        write_pcm(path, samples, profile.sampleRateHz)
        content = path.read_bytes()
        records.append(
            {
                "recordingId": identifier,
                "name": name,
                "sha256": hashlib.sha256(content).hexdigest(),
                "byteLength": len(content),
                "frameCount": samples.shape[0],
                "channelCount": samples.shape[1],
                "sampleRateHz": profile.sampleRateHz,
                "encoding": "wav_pcm_s16le",
                "provenance": {
                    "kind": "FixedPattern",
                    "recipeVersion": "integer-patterns-v1",
                    "patternName": name,
                },
            }
        )
    manifest: dict[str, JsonValue] = {
        "contractVersion": "1",
        "recordings": records,
    }
    validate("RecordingManifest", manifest)
    (directory / "catalog.json").write_text(json.dumps(manifest, indent=2, allow_nan=False) + "\n")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Write the five small Signal Lab fixtures.")
    parser.add_argument("directory", type=Path, help="Host directory for the WAV files and catalog.json")
    write_fixture_catalog(parser.parse_args().directory)
