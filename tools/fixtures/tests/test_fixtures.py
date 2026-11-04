import hashlib
from pathlib import Path

from pydantic import JsonValue, TypeAdapter

from signal_lab_fixtures.contracts import read_json, validate
from signal_lab_fixtures.fixtures import write_fixture_catalog
from signal_lab_fixtures.oracle import analyse_wav, load_profile, result_document


def test_catalog_regenerates_byte_for_byte(tmp_path: Path) -> None:
    first, second = tmp_path / "first", tmp_path / "second"
    assert write_fixture_catalog(first) == write_fixture_catalog(second)
    assert sorted(path.name for path in first.iterdir()) == sorted(path.name for path in second.iterdir())
    for path in first.iterdir():
        assert path.read_bytes() == (second / path.name).read_bytes()


def test_catalog_metadata_and_numerical_outputs_conform(tmp_path: Path) -> None:
    write_fixture_catalog(tmp_path)
    manifest = read_json(tmp_path / "catalog.json")
    validate("RecordingManifest", manifest)
    records = TypeAdapter(list[dict[str, JsonValue]]).validate_python(manifest["recordings"])
    assert len(records) == 5
    for recording in records:
        path = tmp_path / f"{recording['recordingId']}.wav"
        content = path.read_bytes()
        assert len(content) == recording["byteLength"]
        assert hashlib.sha256(content).hexdigest() == recording["sha256"]
        count = TypeAdapter(int).validate_python(recording["channelCount"])
        result = analyse_wav(path, tuple(range(count)), load_profile())
        assert result.frame_count == recording["frameCount"]
        result_document(result)
