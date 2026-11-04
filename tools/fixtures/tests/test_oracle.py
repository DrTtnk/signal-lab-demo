import hashlib
import math
from pathlib import Path

import numpy as np
import pytest

from signal_lab_fixtures.comparison import assert_close
from signal_lab_fixtures.contracts import validate
from signal_lab_fixtures.fixtures import write_pcm
from signal_lab_fixtures.oracle import (
    REFERENCE_LIMIT_BYTES,
    NoPeak,
    Peak,
    analyse_wav,
    load_profile,
    result_document,
    select_peak,
)


@pytest.mark.parametrize(
    "pattern,mean_square,peak_bin",
    [
        ([0, 0, 0, 0], 0.0, -1),
        ([8192, 8192, 8192, 8192], 1 / 16, 0),
        ([8192, -8192, 8192, -8192], 1 / 16, 2048),
        ([0, 8192, 0, -8192], 1 / 32, 1024),
        ([4096, 4096, 4096, -12288], 3 / 64, 1024),
    ],
)
def test_exact_pcm_patterns(tmp_path: Path, pattern: list[int], mean_square: float, peak_bin: int) -> None:
    profile = load_profile()
    samples = np.tile(np.asarray(pattern, dtype=np.int16), profile.windowFrames // 4)
    path = tmp_path / "pattern.wav"
    write_pcm(path, samples.reshape(-1, 1), profile.sampleRateHz)
    result = analyse_wav(path, (0,), profile)
    channel = result.channels[0]
    assert_close("rms", channel.rms, math.sqrt(mean_square))
    assert_close("bandPowers", sum(channel.band_powers), mean_square)
    if peak_bin == -1:
        assert isinstance(channel.peak, NoPeak)
    else:
        assert channel.peak == Peak(peak_bin, peak_bin * profile.sampleRateHz / profile.windowFrames)
    validate("AnalysisResult", result_document(result))


@pytest.mark.parametrize("seed", [0, 1, 7, 23, 431, 2026])
def test_random_pcm_weighted_parseval_and_rms(tmp_path: Path, seed: int) -> None:
    profile = load_profile()
    rng = np.random.default_rng(seed)
    pcm = rng.integers(-32768, 32768, size=(profile.windowFrames * 3 + 11, 2), dtype=np.int16)
    path = tmp_path / "random.wav"
    write_pcm(path, pcm, profile.sampleRateHz)
    result = analyse_wav(path, (1, 0), profile)
    assert result.processed_frames == profile.windowFrames * 3
    assert result.ignored_tail_frames == 11
    assert result.windows == 3
    assert [channel.channel for channel in result.channels] == [1, 0]
    window = (1 - np.cos(2 * np.pi * np.arange(profile.windowFrames) / profile.windowFrames)) / 2
    for channel in result.channels:
        values = pcm[: result.processed_frames, channel.channel].astype(np.float64) / 32768
        integer_square_sum = sum(int(value) ** 2 for value in pcm[: result.processed_frames, channel.channel])
        expected_rms = math.sqrt(integer_square_sum / (32768**2 * result.processed_frames))
        assert_close("rms", channel.rms, expected_rms)
        windows = values.reshape(-1, profile.windowFrames)
        weighted_power = float(np.mean(np.sum((windows * window) ** 2, axis=1) / np.sum(window**2)))
        assert_close("bandPowers", sum(channel.band_powers), weighted_power)
        assert np.all(np.isfinite(channel.mean_psd))
        assert np.all(channel.mean_psd >= 0)
    assert result.sha256 == hashlib.sha256(path.read_bytes()).hexdigest()
    validate("AnalysisResult", result_document(result))


def test_tail_is_excluded_from_both_rms_and_spectrum(tmp_path: Path) -> None:
    profile = load_profile()
    pcm = np.zeros((profile.windowFrames + 3, 1), dtype=np.int16)
    pcm[-3:] = 32767
    path = tmp_path / "tail.wav"
    write_pcm(path, pcm, profile.sampleRateHz)
    result = analyse_wav(path, (0,), profile)
    assert result.channels[0].rms == 0
    assert isinstance(result.channels[0].peak, NoPeak)
    assert result.ignored_tail_frames == 3


def test_peak_ties_select_the_lowest_bin() -> None:
    assert select_peak(np.array([0.0, 2.0, 2.0, 0.0]), 1.0) == Peak(1, 1.0)


@pytest.mark.parametrize("channels", [(), (0, 0), (-1,), (1,)])
def test_invalid_channel_selection(tmp_path: Path, channels: tuple[int, ...]) -> None:
    profile = load_profile()
    path = tmp_path / "mono.wav"
    write_pcm(path, np.zeros((profile.windowFrames, 1), dtype=np.int16), profile.sampleRateHz)
    with pytest.raises(ValueError):
        analyse_wav(path, channels, profile)


def test_insufficient_frames_are_refused(tmp_path: Path) -> None:
    profile = load_profile()
    path = tmp_path / "short.wav"
    write_pcm(
        path,
        np.zeros((profile.windowFrames - 1, 1), dtype=np.int16),
        profile.sampleRateHz,
    )
    with pytest.raises(ValueError, match="complete"):
        analyse_wav(path, (0,), profile)


def test_empty_real_recording_is_refused_as_insufficient(tmp_path: Path) -> None:
    profile = load_profile()
    path = tmp_path / "empty.wav"
    write_pcm(path, np.zeros((0, 1), dtype=np.int16), profile.sampleRateHz)
    with pytest.raises(ValueError, match="complete"):
        analyse_wav(path, (0,), profile)


def test_truncated_real_file_is_refused(tmp_path: Path) -> None:
    profile = load_profile()
    path = tmp_path / "truncated.wav"
    write_pcm(path, np.zeros((profile.windowFrames, 1), dtype=np.int16), profile.sampleRateHz)
    path.write_bytes(path.read_bytes()[:-2])
    with pytest.raises(ValueError, match="Truncated"):
        analyse_wav(path, (0,), profile)


def test_wrong_sample_rate_is_refused(tmp_path: Path) -> None:
    profile = load_profile()
    path = tmp_path / "rate.wav"
    write_pcm(path, np.zeros((profile.windowFrames, 1), dtype=np.int16), 44100)
    with pytest.raises(ValueError, match="Unsupported"):
        analyse_wav(path, (0,), profile)


def test_fixture_bytes_are_reproducible(tmp_path: Path) -> None:
    samples = np.random.default_rng(7).integers(-32768, 32768, size=(4096, 2), dtype=np.int16)
    first, second = tmp_path / "first.wav", tmp_path / "second.wav"
    write_pcm(first, samples, 48000)
    write_pcm(second, samples, 48000)
    assert first.read_bytes() == second.read_bytes()


def test_oracle_refuses_large_files_before_reading_the_payload(tmp_path: Path) -> None:
    path = tmp_path / "large.wav"
    with path.open("wb") as output:
        output.truncate(REFERENCE_LIMIT_BYTES + 1)
    with pytest.raises(ValueError, match="small reference"):
        analyse_wav(path, (0,), load_profile())


def test_hann_can_hide_an_edge_impulse_without_zero_rms(tmp_path: Path) -> None:
    profile = load_profile()
    pcm = np.zeros((profile.windowFrames, 1), dtype=np.int16)
    pcm[0, 0] = 8192
    path = tmp_path / "edge.wav"
    write_pcm(path, pcm, profile.sampleRateHz)
    result = analyse_wav(path, (0,), profile)
    assert_close("rms", result.channels[0].rms, 1 / 256)
    assert isinstance(result.channels[0].peak, NoPeak)
    assert sum(result.channels[0].band_powers) == 0
    result_document(result)


def test_tolerance_policy_detects_wrong_scaling_at_one_pcm_unit(tmp_path: Path) -> None:
    profile = load_profile()
    pcm = np.tile(np.array([0, 1, 0, -1], dtype=np.int16), profile.windowFrames // 4)
    path = tmp_path / "one-unit.wav"
    write_pcm(path, pcm.reshape(-1, 1), profile.sampleRateHz)
    result = analyse_wav(path, (0,), profile)
    channel = result.channels[0]
    with pytest.raises(AssertionError):
        assert_close("rms", channel.rms / 2, channel.rms)
    with pytest.raises(AssertionError):
        assert_close("meanPsd", channel.mean_psd / 2, channel.mean_psd)
    with pytest.raises(AssertionError):
        assert_close("bandPowers", sum(channel.band_powers) / 2, sum(channel.band_powers))


def test_four_channels_are_processed_in_requested_order(tmp_path: Path) -> None:
    profile = load_profile()
    pattern = np.tile(np.array([0, 8192, 0, -8192], dtype=np.int16), profile.windowFrames // 4)
    pcm = np.column_stack((pattern, -pattern, pattern // 2, np.zeros_like(pattern)))
    path = tmp_path / "four.wav"
    write_pcm(path, pcm, profile.sampleRateHz)
    result = analyse_wav(path, (3, 2, 1, 0), profile)
    assert [channel.channel for channel in result.channels] == [3, 2, 1, 0]
    assert isinstance(result.channels[0].peak, NoPeak)
    assert_close("rms", result.channels[1].rms * 2, result.channels[3].rms)
    assert_close("rms", result.channels[2].rms, result.channels[3].rms)
