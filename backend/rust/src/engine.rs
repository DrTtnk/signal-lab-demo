use std::io::{self, Read};

use sha2::{Digest, Sha256};

use crate::contracts::{AnalysisResult, Input, ItemCode, Profile, Refusal};
use rayon::prelude::*;

use crate::dsp::{Channel, Spectrum, Window};

#[derive(Debug, thiserror::Error)]
pub enum EngineError {
    #[error(transparent)]
    Refused(#[from] Refusal),
    #[error(transparent)]
    Io(#[from] io::Error),
    #[error(transparent)]
    Pool(#[from] rayon::ThreadPoolBuildError),
}

/// Windows decoded and analysed in one parallel batch, per worker. Memory depends on this
/// bound, the window size and the channel count, not on the length of the recording.
const WINDOWS_IN_FLIGHT_PER_WORKER: usize = 4;

struct Hashed<R> {
    source: R,
    digest: Sha256,
    bytes: u64,
}

impl<R: Read> Read for Hashed<R> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        let size = self.source.read(buffer)?;
        self.digest.update(&buffer[..size]);
        self.bytes += size as u64;
        Ok(size)
    }
}

struct WavSource<R>(R);

impl<R: Read> Read for WavSource<R> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        let size = self.0.read(buffer)?;
        if size == 0 && !buffer.is_empty() {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "Missing WAV bytes",
            ));
        }
        Ok(size)
    }
}

/// A required read that hits the end of the data is a truncated recording.
fn pcm_error(error: io::Error) -> EngineError {
    if error.kind() == io::ErrorKind::UnexpectedEof {
        Refusal::new(
            ItemCode::TruncatedRecording,
            "WAV data ended before its declared length",
        )
        .into()
    } else {
        error.into()
    }
}

fn wave_error(error: hound::Error) -> EngineError {
    match error {
        hound::Error::IoError(error) if error.kind() == io::ErrorKind::UnexpectedEof => {
            Refusal::new(
                ItemCode::TruncatedRecording,
                "WAV data ended before its declared length",
            )
            .into()
        }
        hound::Error::IoError(error) => error.into(),
        _ => Refusal::new(
            ItemCode::UnsupportedRecording,
            "Invalid or unsupported WAV format",
        )
        .into(),
    }
}

fn samples<R: Read>(
    source: R,
    input: &Input,
    profile: &Profile,
) -> Result<AnalysisResult, EngineError> {
    let wave = hound::WavReader::new(WavSource(source)).map_err(wave_error)?;
    let spec = wave.spec();
    if spec.sample_format != hound::SampleFormat::Int
        || spec.bits_per_sample != profile.pcm_bits
        || spec.sample_rate != profile.sample_rate_hz
        || spec.channels != input.recording.channel_count
        || u64::from(wave.duration()) != input.recording.frame_count
    {
        return Err(Refusal::new(
            ItemCode::UnsupportedRecording,
            "WAV format or frame count does not match the recording snapshot",
        )
        .into());
    }
    if input
        .channels
        .iter()
        .any(|index| *index >= usize::from(spec.channels))
    {
        return Err(Refusal::new(
            ItemCode::ChannelUnavailable,
            "A selected channel is not in this recording",
        )
        .into());
    }
    let size = profile.window_frames;
    let frames = u64::from(wave.duration());
    let windows = frames / size as u64;
    if windows == 0 {
        return Err(Refusal::new(
            ItemCode::InsufficientFrames,
            "Recording has no complete analysis window",
        )
        .into());
    }
    let selected_count = input.channels.len();
    let mut channels = input
        .channels
        .iter()
        .map(|index| Channel::new(*index, size))
        .collect::<Vec<_>>();
    let mut selected = vec![None; usize::from(spec.channels)];
    for (slot, index) in input.channels.iter().enumerate() {
        selected[*index] = Some(slot);
    }
    let workers = usize::from(input.workers);
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(workers)
        .build()?;
    let spectrum = Spectrum::new(profile);
    let in_flight = WINDOWS_IN_FLIGHT_PER_WORKER * workers;
    let mut batch = (0..in_flight * selected_count)
        .map(|_| Window::new(size))
        .collect::<Vec<_>>();
    let channel_count = usize::from(spec.channels);
    let mut source = wave.into_inner();
    let mut raw = vec![0_u8; size * channel_count * 2];
    let mut remaining = windows as usize;
    while remaining > 0 {
        let windows_here = remaining.min(in_flight);
        for window in 0..windows_here {
            source.read_exact(&mut raw).map_err(pcm_error)?;
            for (frame, samples) in raw.chunks_exact(channel_count * 2).enumerate() {
                for (slot, bytes) in selected.iter().zip(samples.chunks_exact(2)) {
                    if let Some(index) = slot {
                        batch[window * selected_count + index].samples[frame] =
                            i16::from_le_bytes([bytes[0], bytes[1]]);
                    }
                }
            }
        }
        let units = &mut batch[..windows_here * selected_count];
        pool.install(|| {
            units.par_iter_mut().for_each_init(
                || spectrum.workspace(),
                |workspace, unit| spectrum.analyse(workspace, unit),
            );
        });
        for window in units.chunks(selected_count) {
            for (channel, unit) in channels.iter_mut().zip(window) {
                channel.add(unit);
            }
        }
        remaining -= windows_here;
    }
    let tail_bytes = (frames % size as u64) as usize * channel_count * 2;
    source
        .read_exact(&mut vec![0_u8; tail_bytes])
        .map_err(pcm_error)?;
    Ok(AnalysisResult {
        processed_frames: windows * size as u64,
        ignored_tail_frames: frames % size as u64,
        processed_windows: windows,
        channels: channels
            .into_iter()
            .map(|channel| channel.finish(windows, profile))
            .collect(),
    })
}

pub fn analyse<R: Read>(
    source: R,
    input: &Input,
    profile: &Profile,
) -> Result<AnalysisResult, EngineError> {
    let mut source = Hashed {
        source: source.take(input.recording.byte_length + 1),
        digest: Sha256::new(),
        bytes: 0,
    };
    let mut prefix = [0_u8; 12];
    source.read_exact(&mut prefix).map_err(|error| {
        if error.kind() == io::ErrorKind::UnexpectedEof {
            EngineError::from(Refusal::new(
                ItemCode::TruncatedRecording,
                "Incomplete RIFF header",
            ))
        } else {
            EngineError::from(error)
        }
    })?;
    let declared_bytes = hound::read_wave_header(&mut prefix.as_slice()).map_err(wave_error)?;
    if declared_bytes != input.recording.byte_length {
        return Err(Refusal::new(
            ItemCode::UnsupportedRecording,
            "RIFF byte count does not match the recording snapshot",
        )
        .into());
    }
    let result = samples(prefix.as_slice().chain(&mut source), input, profile)?;
    io::copy(&mut source, &mut io::sink())?;
    if source.bytes < input.recording.byte_length {
        return Err(Refusal::new(
            ItemCode::TruncatedRecording,
            "Recording ended before its declared byte count",
        )
        .into());
    }
    if source.bytes != input.recording.byte_length
        || format!("{:x}", source.digest.finalize()) != input.recording.sha256
    {
        return Err(Refusal::new(
            ItemCode::DigestMismatch,
            "Recording byte count or SHA-256 does not match its snapshot",
        )
        .into());
    }
    Ok(result)
}
