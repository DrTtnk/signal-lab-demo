import hashlib
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import numpy as np
from numpy.typing import NDArray
from pydantic import BaseModel, ConfigDict, JsonValue
from scipy import signal

from signal_lab_fixtures.contracts import CONTRACT_ROOT, read_json, validate, validate_result

REFERENCE_LIMIT_BYTES = 1_048_576


class Profile(BaseModel):
    model_config = ConfigDict(strict=True, frozen=True, extra="forbid")

    id: str
    sampleRateHz: int
    windowFrames: int
    overlapFrames: int
    pcmBits: int
    pcmDivisor: int
    window: Literal["periodic_hann"]
    detrend: Literal[False]
    spectrum: Literal["one_sided_density"]
    average: Literal["arithmetic_mean"]
    accumulation: Literal["f64"]
    bands: tuple[tuple[int, int], tuple[int, int], tuple[int, int]]


def load_profile() -> Profile:
    path = CONTRACT_ROOT / "profile.json"
    validate("Profile", read_json(path))
    return Profile.model_validate_json(path.read_text())


@dataclass(frozen=True)
class NoPeak:
    pass


@dataclass(frozen=True)
class Peak:
    bin: int
    frequency_hz: float


@dataclass(frozen=True)
class ChannelResult:
    channel: int
    rms: float
    mean_psd: NDArray[np.float64]
    band_powers: tuple[float, float, float]
    peak: Peak | NoPeak


@dataclass(frozen=True)
class ReferenceResult:
    sha256: str
    byte_length: int
    frame_count: int
    channel_count: int
    processed_frames: int
    ignored_tail_frames: int
    windows: int
    channels: tuple[ChannelResult, ...]


def select_peak(psd: NDArray[np.float64], bin_hz: float) -> Peak | NoPeak:
    if not np.any(psd):
        return NoPeak()
    index = int(np.argmax(psd))
    return Peak(index, index * bin_hz)


def analyse_wav(path: Path, channels: tuple[int, ...], profile: Profile) -> ReferenceResult:
    if path.stat().st_size > REFERENCE_LIMIT_BYTES:
        raise ValueError("The numerical oracle accepts only small reference files")
    if not channels or len(set(channels)) != len(channels):
        raise ValueError("Select at least one unique channel")
    with wave.open(str(path), "rb") as recording:
        count = recording.getnchannels()
        frames = recording.getnframes()
        if (
            recording.getsampwidth() != 2
            or recording.getcomptype() != "NONE"
            or recording.getframerate() != profile.sampleRateHz
            or not 1 <= count <= 4
        ):
            raise ValueError("Unsupported recording format")
        if any(channel < 0 or channel >= count for channel in channels):
            raise ValueError("Selected channel is unavailable")
        if frames < profile.windowFrames:
            raise ValueError("Recording has no complete analysis window")
        raw = recording.readframes(frames)
    if len(raw) != frames * count * 2:
        raise ValueError("Truncated PCM payload")
    pcm = np.frombuffer(raw, dtype=np.dtype(np.int16).newbyteorder("<"))
    pcm = pcm.astype(np.int16, copy=False).reshape(frames, count)
    used = frames - frames % profile.windowFrames
    spacing = profile.sampleRateHz / profile.windowFrames
    window = signal.windows.hann(profile.windowFrames, sym=False)
    results: list[ChannelResult] = []
    for channel in channels:
        samples = pcm[:used, channel].astype(np.float64) / profile.pcmDivisor
        _, psd = signal.welch(
            samples,
            fs=profile.sampleRateHz,
            window=window,
            nperseg=profile.windowFrames,
            noverlap=profile.overlapFrames,
            nfft=profile.windowFrames,
            detrend=profile.detrend,
            return_onesided=True,
            scaling="density",
            average="mean",
        )
        powers = tuple(float(np.sum(psd[start:end])) * spacing for start, end in profile.bands)
        psd.setflags(write=False)
        results.append(
            ChannelResult(
                channel,
                float(np.sqrt(np.mean(np.square(samples)))),
                psd,
                (powers[0], powers[1], powers[2]),
                select_peak(psd, spacing),
            )
        )
    return ReferenceResult(
        hashlib.sha256(path.read_bytes()).hexdigest(),
        path.stat().st_size,
        frames,
        count,
        used,
        frames - used,
        used // profile.windowFrames,
        tuple(results),
    )


def result_document(result: ReferenceResult) -> dict[str, JsonValue]:
    channels: list[JsonValue] = []
    for channel in result.channels:
        peak: dict[str, JsonValue]
        if isinstance(channel.peak, Peak):
            peak = {
                "kind": "Peak",
                "bin": channel.peak.bin,
                "frequencyHz": channel.peak.frequency_hz,
            }
        else:
            peak = {"kind": "NoPeak"}
        channels.append(
            {
                "channelIndex": channel.channel,
                "rms": channel.rms,
                "meanPsd": [float(value) for value in channel.mean_psd],
                "bandPowers": list(channel.band_powers),
                "peak": peak,
            }
        )
    document: dict[str, JsonValue] = {
        "processedFrames": result.processed_frames,
        "ignoredTailFrames": result.ignored_tail_frames,
        "processedWindows": result.windows,
        "channels": channels,
    }
    validate_result(document)
    return document
