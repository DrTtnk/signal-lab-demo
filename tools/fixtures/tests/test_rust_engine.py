import hashlib
import json
import subprocess
from pathlib import Path
from uuid import UUID

import numpy as np
import pytest
from pydantic import JsonValue, TypeAdapter

from signal_lab_fixtures.comparison import assert_close
from signal_lab_fixtures.contracts import ROOT, validate_engine_output
from signal_lab_fixtures.fixtures import write_fixture_catalog, write_pcm
from signal_lab_fixtures.oracle import analyse_wav, load_profile, result_document

ENGINE = ROOT / "backend/rust/target/debug/signal-lab-engine"
OBJECT = TypeAdapter(dict[str, JsonValue])
OBJECTS = TypeAdapter(list[dict[str, JsonValue]])
NUMBERS = TypeAdapter(list[float])
NUMBER = TypeAdapter(float)


def control_for(path: Path, frames: int, channels: int) -> dict[str, JsonValue]:
    raw = path.read_bytes()
    return {
        "contractVersion": "1",
        "profileId": load_profile().id,
        "itemId": str(UUID(int=101)),
        "recording": {
            "recordingId": str(UUID(int=102)),
            "name": "generated-case",
            "sha256": hashlib.sha256(raw).hexdigest(),
            "byteLength": len(raw),
            "frameCount": frames,
            "channelCount": channels,
            "sampleRateHz": 48000,
            "encoding": "wav_pcm_s16le",
            "provenance": {
                "kind": "Seeded",
                "recipeVersion": "rust-tests-v1",
                "seed": 7,
            },
        },
        "channels": list(reversed(range(channels))),
        "workers": 1,
    }


def run_raw(payload: bytes) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        [str(ENGINE), "analyse"],
        input=payload,
        capture_output=True,
        timeout=15,
        check=False,
    )


def run_item(control: dict[str, JsonValue], raw: bytes) -> dict[str, JsonValue]:
    completed = run_raw(json.dumps(control, allow_nan=False).encode() + b"\n" + raw)
    assert completed.returncode in (0, 2), completed.stderr.decode()
    assert completed.stderr == b""
    assert completed.stdout.count(b"\n") == 1
    output = OBJECT.validate_json(completed.stdout, strict=True)
    validate_engine_output(control, output)
    assert (completed.returncode == 0) == (output["state"] == "Succeeded")
    return output


def compare_with_oracle(path: Path, control: dict[str, JsonValue]) -> None:
    actual = run_item(control, path.read_bytes())
    assert actual["state"] == "Succeeded"
    channels = TypeAdapter(tuple[int, ...]).validate_python(control["channels"])
    reference = result_document(analyse_wav(path, channels, load_profile()))
    result = OBJECT.validate_python(actual["result"], strict=True)
    for name in ("processedFrames", "ignoredTailFrames", "processedWindows"):
        assert result[name] == reference[name]
    emitted = OBJECTS.validate_python(result["channels"], strict=True)
    expected = OBJECTS.validate_python(reference["channels"], strict=True)
    for got, wanted in zip(emitted, expected, strict=True):
        assert got["channelIndex"] == wanted["channelIndex"]
        assert_close(
            "rms",
            NUMBER.validate_python(got["rms"]),
            NUMBER.validate_python(wanted["rms"]),
        )
        for key in ("meanPsd", "bandPowers"):
            assert_close(
                key,
                np.asarray(NUMBERS.validate_python(got[key]), dtype=np.float64),
                np.asarray(NUMBERS.validate_python(wanted[key]), dtype=np.float64),
            )
        assert got["peak"] == wanted["peak"]


@pytest.mark.parametrize("index", range(5))
def test_catalog_fixtures(tmp_path: Path, index: int) -> None:
    manifest = write_fixture_catalog(tmp_path)
    recording = OBJECTS.validate_python(manifest["recordings"], strict=True)[index]
    path = tmp_path / f"{recording['recordingId']}.wav"
    control = control_for(path, int(str(recording["frameCount"])), int(str(recording["channelCount"])))
    control["recording"] = recording
    compare_with_oracle(path, control)


@pytest.mark.parametrize("seed", [0, 1, 7, 23, 431, 2026])
def test_random_multichannel_windows_and_tail(tmp_path: Path, seed: int) -> None:
    frames, channels = load_profile().windowFrames * 3 + 11, 4
    pcm = np.random.default_rng(seed).integers(-32768, 32768, (frames, channels), dtype=np.int16)
    path = tmp_path / "random.wav"
    write_pcm(path, pcm, 48000)
    compare_with_oracle(path, control_for(path, frames, channels))


@pytest.mark.parametrize("case", ["impulse", "one-unit", "min", "max", "tail", "two-tones"])
def test_numerical_edges(tmp_path: Path, case: str) -> None:
    size = load_profile().windowFrames
    pcm = np.zeros((size + 3, 1), dtype=np.int16)
    if case == "impulse":
        pcm[0] = 8192
    elif case == "one-unit":
        pcm[:size, 0] = np.tile(np.array([0, 1, 0, -1], dtype=np.int16), size // 4)
    elif case == "min":
        pcm[:size] = -32768
    elif case == "max":
        pcm[:size] = 32767
    elif case == "tail":
        pcm[size:] = 32767
    else:
        pcm[:size, 0] = np.tile(np.array([4096, 4096, 4096, -12288], dtype=np.int16), size // 4)
    path = tmp_path / "edge.wav"
    write_pcm(path, pcm, 48000)
    compare_with_oracle(path, control_for(path, len(pcm), 1))


def with_workers(control: dict[str, JsonValue], workers: int) -> dict[str, JsonValue]:
    return {**control, "workers": workers}


@pytest.mark.parametrize("workers", [2, 4])
@pytest.mark.parametrize("windows,channels,tail", [(1, 1, 0), (33, 1, 0), (21, 4, 11), (7, 2, 4095)])
def test_workers_give_identical_results(tmp_path: Path, workers: int, windows: int, channels: int, tail: int) -> None:
    frames = load_profile().windowFrames * windows + tail
    pcm = np.random.default_rng(windows * 10 + channels).integers(-32768, 32768, (frames, channels), dtype=np.int16)
    path = tmp_path / "workers.wav"
    write_pcm(path, pcm, 48000)
    single = control_for(path, frames, channels)
    parallel = with_workers(single, workers)

    expected = run_item(single, path.read_bytes())
    actual = run_item(parallel, path.read_bytes())

    assert actual["result"] == expected["result"]
    assert actual["workers"] == workers
    compare_with_oracle(path, parallel)


@pytest.mark.parametrize("workers", [0, 3, 8])
def test_other_worker_counts_are_refused(tmp_path: Path, workers: int) -> None:
    path = tmp_path / "input.wav"
    write_pcm(path, np.zeros((4096, 1), dtype=np.int16), 48000)
    control = with_workers(control_for(path, 4096, 1), workers)
    completed = run_raw(json.dumps(control).encode() + b"\n" + path.read_bytes())
    assert completed.returncode == 64
    assert completed.stdout == b""


@pytest.mark.parametrize("frames", [0, 4095])
def test_insufficient_frames(tmp_path: Path, frames: int) -> None:
    path = tmp_path / "short.wav"
    write_pcm(path, np.zeros((frames, 1), dtype=np.int16), 48000)
    output = run_item(control_for(path, frames, 1), path.read_bytes())
    assert OBJECT.validate_python(output["error"])["code"] == "insufficient_frames"


@pytest.mark.parametrize(
    "change,code",
    [
        ("truncated", "truncated_recording"),
        ("digest", "digest_mismatch"),
        ("extra", "digest_mismatch"),
        ("channel", "channel_unavailable"),
        ("rate", "unsupported_recording"),
        ("metadata", "unsupported_recording"),
        ("not-wav", "unsupported_recording"),
    ],
)
def test_typed_refusals(tmp_path: Path, change: str, code: str) -> None:
    path = tmp_path / "input.wav"
    write_pcm(path, np.zeros((4096, 1), dtype=np.int16), 44100 if change == "rate" else 48000)
    control = control_for(path, 4096, 1)
    raw = path.read_bytes()
    recording = OBJECT.validate_python(control["recording"])
    if change == "truncated":
        raw = raw[:-2]
    elif change == "digest":
        recording["sha256"] = "0" * 64
    elif change == "extra":
        raw += b"extra"
    elif change == "channel":
        control["channels"] = [1]
    elif change == "metadata":
        recording["frameCount"] = 8192
    elif change == "not-wav":
        raw = b"not a wave recording"
        recording["sha256"] = hashlib.sha256(raw).hexdigest()
        recording["byteLength"] = len(raw)
    control["recording"] = recording
    output = run_item(control, raw)
    assert OBJECT.validate_python(output["error"])["code"] == code


@pytest.mark.parametrize(
    "change",
    [
        "extra-key",
        "null",
        "uuid",
        "duplicate-channel",
        "workers",
        "fraction",
        "boolean",
        "version",
        "oversize",
        "no-lf",
        "nonfinite",
    ],
)
def test_malformed_control_has_no_json_result(tmp_path: Path, change: str) -> None:
    path = tmp_path / "input.wav"
    write_pcm(path, np.zeros((4096, 1), dtype=np.int16), 48000)
    control = control_for(path, 4096, 1)
    if change == "extra-key":
        control["extra"] = 1
    elif change == "null":
        control["channels"] = None
    elif change == "uuid":
        control["itemId"] = "not-a-uuid"
    elif change == "duplicate-channel":
        control["channels"] = [0, 0]
    elif change == "workers":
        control["workers"] = 3
    elif change == "fraction":
        control["workers"] = 1.5
    elif change == "boolean":
        control["workers"] = True
    elif change == "version":
        control["contractVersion"] = "2"
    header = json.dumps(control).encode() + b"\n"
    if change == "oversize":
        header = b" " * 65536 + header
    elif change == "no-lf":
        header = header[:-1]
    elif change == "nonfinite":
        header = header.replace(b'"workers": 1', b'"workers": 1e999')
    completed = run_raw(header + (b"" if change == "no-lf" else path.read_bytes()))
    assert completed.returncode == 64
    assert completed.stdout == b""
    assert completed.stderr


def test_schema_integer_semantics(tmp_path: Path) -> None:
    path = tmp_path / "input.wav"
    write_pcm(path, np.zeros((4096, 1), dtype=np.int16), 48000)
    control = control_for(path, 4096, 1)
    control["workers"] = 1.0
    control["channels"] = [0.0]
    recording = OBJECT.validate_python(control["recording"])
    recording["frameCount"] = 4096.0
    control["recording"] = recording
    compare_with_oracle(path, control)


def test_pcm_length_beyond_riff_is_a_recording_refusal(tmp_path: Path) -> None:
    path = tmp_path / "inconsistent.wav"
    write_pcm(path, np.zeros((4096, 1), dtype=np.int16), 48000)
    raw = bytearray(path.read_bytes())
    raw[40:44] = (4096 * 2 * 2).to_bytes(4, "little")
    path.write_bytes(raw)
    output = run_item(control_for(path, 8192, 1), bytes(raw))
    assert OBJECT.validate_python(output["error"])["code"] == "truncated_recording"


def test_a_chunk_before_the_data_chunk_is_skipped(tmp_path: Path) -> None:
    frames, channels = load_profile().windowFrames * 2 + 5, 2
    pcm = np.random.default_rng(3).integers(-32768, 32768, (frames, channels), dtype=np.int16)
    path = tmp_path / "list-before-data.wav"
    write_pcm(path, pcm, 48000)
    raw = path.read_bytes()
    assert raw[36:40] == b"data"
    extra = b"LIST" + (4).to_bytes(4, "little") + b"INFO"
    changed = bytearray(raw[:36] + extra + raw[36:])
    changed[4:8] = (len(changed) - 8).to_bytes(4, "little")
    path.write_bytes(changed)
    compare_with_oracle(path, control_for(path, frames, channels))


@pytest.mark.parametrize("cut", [1, 3, 5000, 40000])
def test_a_recording_cut_at_any_offset_is_truncated(tmp_path: Path, cut: int) -> None:
    frames, channels = load_profile().windowFrames * 3 + 7, 2
    pcm = np.random.default_rng(cut).integers(-32768, 32768, (frames, channels), dtype=np.int16)
    path = tmp_path / "cut.wav"
    write_pcm(path, pcm, 48000)
    control = control_for(path, frames, channels)
    output = run_item(control, path.read_bytes()[:-cut])
    assert OBJECT.validate_python(output["error"])["code"] == "truncated_recording"


@pytest.mark.parametrize("change", ["valid", "changed", "truncated"])
def test_trailing_riff_metadata_is_hashed(tmp_path: Path, change: str) -> None:
    path = tmp_path / "metadata.wav"
    write_pcm(path, np.zeros((4096, 1), dtype=np.int16), 48000)
    raw = bytearray(path.read_bytes() + b"JUNK" + (4).to_bytes(4, "little") + b"abcd")
    raw[4:8] = (len(raw) - 8).to_bytes(4, "little")
    path.write_bytes(raw)
    control = control_for(path, 4096, 1)
    if change == "valid":
        compare_with_oracle(path, control)
    else:
        payload = bytes(raw[:-1]) if change == "truncated" else bytes(raw[:-1]) + b"z"
        output = run_item(control, payload)
        expected = "truncated_recording" if change == "truncated" else "digest_mismatch"
        assert OBJECT.validate_python(output["error"])["code"] == expected
