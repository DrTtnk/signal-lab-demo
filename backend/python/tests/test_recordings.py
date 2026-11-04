import json

import psycopg
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import text
from sqlalchemy.engine import Engine

from signal_lab_recordings.app import build_app
from signal_lab_recordings.contracts import validate
from signal_lab_recordings.settings import Settings
from tests.support import MONO, STEREO, recording_entry, write_catalog


def test_startup_registers_the_manifest_and_reports_ready(settings: Settings) -> None:
    manifest = write_catalog(settings.recordings_dir)
    with TestClient(build_app(settings)) as client:
        assert client.get("/health").json() == {"status": "ready"}
        listed = client.get("/recordings")
    assert listed.status_code == 200
    assert listed.json() == manifest["recordings"]


def test_responses_conform_to_the_canonical_contract(settings: Settings) -> None:
    write_catalog(settings.recordings_dir)
    with TestClient(build_app(settings)) as client:
        for recording in client.get("/recordings").json():
            validate(settings.contracts_dir, "Recording", recording)
        validate(settings.contracts_dir, "Recording", client.get(f"/recordings/{MONO}").json())


def test_content_streams_the_exact_managed_bytes(settings: Settings) -> None:
    write_catalog(settings.recordings_dir)
    with TestClient(build_app(settings)) as client:
        response = client.get(f"/recordings/{STEREO}/content")
    assert response.status_code == 200
    assert response.content == (settings.recordings_dir / f"{STEREO}.wav").read_bytes()


def test_unknown_recording_is_not_found(settings: Settings) -> None:
    write_catalog(settings.recordings_dir)
    with TestClient(build_app(settings)) as client:
        assert client.get("/recordings/00000000-0000-0000-0000-0000000000ff").status_code == 404
        assert client.get("/recordings/00000000-0000-0000-0000-0000000000ff/content").status_code == 404


@pytest.mark.parametrize("identifier", ["../catalog.json", "not-a-uuid", "..%2Fcatalog.json"])
def test_identifier_is_never_a_path(settings: Settings, identifier: str) -> None:
    write_catalog(settings.recordings_dir)
    with TestClient(build_app(settings)) as client:
        assert client.get(f"/recordings/{identifier}/content").status_code in (404, 422)


def test_registration_is_idempotent(settings: Settings, engine: Engine) -> None:
    write_catalog(settings.recordings_dir)
    for _ in range(2):
        with TestClient(build_app(settings)):
            pass
    with engine.connect() as connection:
        count = connection.execute(text("SELECT count(*) FROM recordings.recording")).scalar_one()
    assert count == 2


def test_conflicting_content_for_a_known_identifier_stops_startup(settings: Settings) -> None:
    write_catalog(settings.recordings_dir)
    with TestClient(build_app(settings)):
        pass
    (settings.recordings_dir / f"{MONO}.wav").unlink()
    manifest = json.loads((settings.recordings_dir / "catalog.json").read_text())
    manifest["recordings"][0] = recording_entry(settings.recordings_dir, MONO, "mono", 8192, 1)
    (settings.recordings_dir / "catalog.json").write_text(json.dumps(manifest))
    with pytest.raises(Exception, match="conflicts"), TestClient(build_app(settings)):
        pass


def test_file_that_disagrees_with_the_manifest_stops_startup(settings: Settings) -> None:
    write_catalog(settings.recordings_dir)
    path = settings.recordings_dir / f"{MONO}.wav"
    path.write_bytes(path.read_bytes() + b"\x00\x00")
    with pytest.raises(Exception, match="digest"), TestClient(build_app(settings)):
        pass


def test_missing_file_stops_startup(settings: Settings) -> None:
    write_catalog(settings.recordings_dir)
    (settings.recordings_dir / f"{STEREO}.wav").unlink()
    with pytest.raises(FileNotFoundError), TestClient(build_app(settings)):
        pass


def test_manifest_with_an_unknown_field_stops_startup(settings: Settings) -> None:
    manifest = write_catalog(settings.recordings_dir)
    manifest["extra"] = 1
    (settings.recordings_dir / "catalog.json").write_text(json.dumps(manifest))
    with pytest.raises(Exception, match="extra"), TestClient(build_app(settings)):
        pass


def test_service_role_cannot_use_another_service_schema(settings: Settings) -> None:
    url = settings.database_url.replace("+psycopg", "")
    with psycopg.connect(url) as connection, pytest.raises(psycopg.errors.InsufficientPrivilege):
        connection.execute("SELECT * FROM analysis.anything")


def test_same_size_content_change_stops_startup(settings: Settings) -> None:
    write_catalog(settings.recordings_dir)
    path = settings.recordings_dir / f"{MONO}.wav"
    content = bytearray(path.read_bytes())
    content[-1] ^= 0xFF
    path.write_bytes(content)
    with pytest.raises(Exception, match="digest"), TestClient(build_app(settings)):
        pass
