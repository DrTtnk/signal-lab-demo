import hashlib
import json
import wave
from pathlib import Path

from sqlalchemy import insert, select
from sqlalchemy.engine import Connection, Engine

from signal_lab_recordings.contracts import validate
from signal_lab_recordings.database import recording
from signal_lab_recordings.models import Manifest, Recording
from signal_lab_recordings.settings import Settings


class CatalogueError(Exception):
    pass


def reject_constant(value: str) -> object:
    raise ValueError(f"Non-finite JSON number: {value}")


def read_manifest(settings: Settings) -> Manifest:
    text = (settings.recordings_dir / "catalog.json").read_text()
    validate(settings.contracts_dir, "RecordingManifest", json.loads(text, parse_constant=reject_constant))
    return Manifest.model_validate_json(text)


def verify_file(directory: Path, entry: Recording) -> None:
    path = directory / f"{entry.recording_id}.wav"
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != entry.sha256 or path.stat().st_size != entry.byte_length:
        raise CatalogueError(f"Recording {entry.recording_id}: file digest or size differs from the manifest")
    with wave.open(str(path), "rb") as header:
        actual = (header.getnchannels(), header.getnframes(), header.getframerate(), header.getsampwidth())
    if actual != (entry.channel_count, entry.frame_count, entry.sample_rate_hz, 2):
        raise CatalogueError(f"Recording {entry.recording_id}: WAV header differs from the manifest")


def register_one(connection: Connection, entry: Recording) -> None:
    existing = connection.execute(select(recording).where(recording.c.recording_id == entry.recording_id)).one_or_none()
    if existing is None:
        connection.execute(insert(recording).values(entry.model_dump()))
    elif Recording.model_validate(existing) != entry:
        raise CatalogueError(f"Recording {entry.recording_id} conflicts with the registered snapshot")


def register(engine: Engine, settings: Settings) -> None:
    entries = read_manifest(settings).recordings
    for entry in entries:
        verify_file(settings.recordings_dir, entry)
    with engine.begin() as connection:
        for entry in entries:
            register_one(connection, entry)
