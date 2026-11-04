import hashlib
import json
import wave
from pathlib import Path
from uuid import UUID

from pydantic import JsonValue

MONO = UUID(int=1)
STEREO = UUID(int=2)


def write_wav(path: Path, frames: int, channels: int) -> None:
    with wave.open(str(path), "wb") as recording:
        recording.setnchannels(channels)
        recording.setsampwidth(2)
        recording.setframerate(48000)
        recording.writeframes(bytes(frames * channels * 2))


def recording_entry(directory: Path, identifier: UUID, name: str, frames: int, channels: int):
    path = directory / f"{identifier}.wav"
    write_wav(path, frames, channels)
    content = path.read_bytes()
    entry: dict[str, JsonValue] = {
        "recordingId": str(identifier),
        "name": name,
        "sha256": hashlib.sha256(content).hexdigest(),
        "byteLength": len(content),
        "frameCount": frames,
        "channelCount": channels,
        "sampleRateHz": 48000,
        "encoding": "wav_pcm_s16le",
        "provenance": {
            "kind": "Seeded",
            "recipeVersion": "service-tests-v1",
            "seed": identifier.int,
        },
    }
    return entry


def write_catalog(directory: Path) -> dict[str, JsonValue]:
    records: list[JsonValue] = [
        recording_entry(directory, MONO, "mono", 4096, 1),
        recording_entry(directory, STEREO, "stereo", 4099, 2),
    ]
    manifest: dict[str, JsonValue] = {"contractVersion": "1", "recordings": records}
    (directory / "catalog.json").write_text(json.dumps(manifest))
    return manifest
