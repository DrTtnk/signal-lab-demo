use std::error::Error;
use std::hint::black_box;
use std::io::{BufReader, Cursor, Read};

use criterion::{Criterion, Throughput, criterion_group, criterion_main};
use serde_json::json;
use sha2::{Digest, Sha256};
use signal_lab_engine::contracts::{Input, Profile};
use signal_lab_engine::dsp::{Spectrum, Window};
use signal_lab_engine::engine;

const CHANNELS: u16 = 4;
const SEED: u64 = 0x5EED_5167_A11A_B001;

struct Recording {
    bytes: Vec<u8>,
    input: Input,
    windows: u64,
}

fn sample(state: &mut u64, frame: u64, channel: u16) -> i16 {
    *state ^= *state << 13;
    *state ^= *state >> 7;
    *state ^= *state << 17;
    let phase = frame as f64 / 48_000.0;
    let tone = (std::f64::consts::TAU * (440.0 + 110.0 * f64::from(channel)) * phase).sin()
        + 0.5 * (std::f64::consts::TAU * 6_000.0 * phase).sin();
    let noise = (*state >> 40) as f64 / (1_u64 << 24) as f64 - 0.5;
    ((tone * 0.3 + noise * 0.1) * 32_767.0) as i16
}

fn recording(profile: &Profile, windows: u64, workers: u8) -> Result<Recording, Box<dyn Error>> {
    let frames = windows * profile.window_frames as u64;
    let spec = hound::WavSpec {
        channels: CHANNELS,
        sample_rate: profile.sample_rate_hz,
        bits_per_sample: profile.pcm_bits,
        sample_format: hound::SampleFormat::Int,
    };
    let mut cursor = Cursor::new(Vec::new());
    let mut writer = hound::WavWriter::new(&mut cursor, spec)?;
    let mut state = SEED;
    for frame in 0..frames {
        for channel in 0..CHANNELS {
            writer.write_sample(sample(&mut state, frame, channel))?;
        }
    }
    writer.finalize()?;
    let bytes = cursor.into_inner();
    let input = serde_json::from_value(json!({
        "profileId": profile.id,
        "itemId": "benchmark-item",
        "recording": {
            "recordingId": "benchmark-recording",
            "sha256": format!("{:x}", Sha256::digest(&bytes)),
            "byteLength": bytes.len(),
            "frameCount": frames,
            "channelCount": CHANNELS,
            "provenance": { "kind": "Seeded", "recipeVersion": "benchmark-v1", "seed": SEED },
        },
        "channels": (0..CHANNELS).collect::<Vec<_>>(),
        "workers": workers,
    }))?;
    Ok(Recording {
        bytes,
        input,
        windows,
    })
}

fn kernel(criterion: &mut Criterion) -> Result<(), Box<dyn Error>> {
    let profile = Profile::load()?;
    let spectrum = Spectrum::new(&profile);
    let mut workspace = spectrum.workspace();
    let mut window = Window::new(profile.window_frames);
    let mut state = SEED;
    for (frame, slot) in window.samples.iter_mut().enumerate() {
        *slot = sample(&mut state, frame as u64, 0);
    }
    let mut group = criterion.benchmark_group("kernel");
    group.throughput(Throughput::Elements(profile.window_frames as u64));
    group.bench_function("one_window_one_channel", |bencher| {
        bencher.iter(|| spectrum.analyse(&mut workspace, black_box(&mut window)));
    });
    group.finish();
    Ok(())
}

fn analyse(criterion: &mut Criterion) -> Result<(), Box<dyn Error>> {
    let profile = Profile::load()?;
    let mut group = criterion.benchmark_group("analyse_four_channels");
    for windows in [16_u64, 256, 1024] {
        for workers in [1_u8, 2, 4] {
            let recording = recording(&profile, windows, workers)?;
            group.throughput(Throughput::Bytes(recording.bytes.len() as u64));
            group.bench_function(format!("{windows}_windows_{workers}_workers"), |bencher| {
                bencher.iter(|| {
                    let result = match engine::analyse(
                        Cursor::new(black_box(&recording.bytes)),
                        &recording.input,
                        &profile,
                    ) {
                        Ok(result) => result,
                        Err(error) => panic!("benchmark input was refused: {error}"),
                    };
                    assert_eq!(result.processed_windows, recording.windows);
                    result
                });
            });
        }
    }
    group.finish();
    Ok(())
}

/// Hashes every byte that passes through, like the engine's reader does.
struct Hashed<R> {
    source: R,
    digest: Sha256,
}

impl<R: Read> Read for Hashed<R> {
    fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
        let size = self.source.read(buffer)?;
        self.digest.update(&buffer[..size]);
        Ok(size)
    }
}

fn decode<R: Read>(source: R) -> Result<i64, Box<dyn Error>> {
    let mut wave = hound::WavReader::new(source)?;
    let mut total = 0_i64;
    for sample in wave.samples::<i16>() {
        total += i64::from(sample?);
    }
    Ok(total)
}

/// The parts of the single-worker run that are not the FFT, one by one.
fn sequential_parts(criterion: &mut Criterion) -> Result<(), Box<dyn Error>> {
    let profile = Profile::load()?;
    let recording = recording(&profile, 1024, 1)?;
    let bytes = &recording.bytes;
    let mut group = criterion.benchmark_group("sequential_parts_1024_windows");
    group.throughput(Throughput::Bytes(bytes.len() as u64));
    group.bench_function("sha256_one_shot", |bencher| {
        bencher.iter(|| Sha256::digest(black_box(bytes)));
    });
    group.bench_function("decode_only", |bencher| {
        bencher.iter(|| match decode(Cursor::new(black_box(bytes))) {
            Ok(total) => total,
            Err(error) => panic!("decode failed: {error}"),
        });
    });
    group.bench_function("decode_through_hash_unbuffered", |bencher| {
        bencher.iter(|| {
            let source = Hashed {
                source: Cursor::new(black_box(bytes)),
                digest: Sha256::new(),
            };
            match decode(source) {
                Ok(total) => total,
                Err(error) => panic!("decode failed: {error}"),
            }
        });
    });
    group.bench_function("decode_through_hash_buffered", |bencher| {
        bencher.iter(|| {
            let source = Hashed {
                source: Cursor::new(black_box(bytes)),
                digest: Sha256::new(),
            };
            match decode(BufReader::with_capacity(64 * 1024, source)) {
                Ok(total) => total,
                Err(error) => panic!("decode failed: {error}"),
            }
        });
    });
    group.finish();
    Ok(())
}

fn all(criterion: &mut Criterion) {
    if let Err(error) = kernel(criterion)
        .and_then(|()| analyse(criterion))
        .and_then(|()| sequential_parts(criterion))
    {
        panic!("benchmark setup failed: {error}");
    }
}

criterion_group!(benches, all);
criterion_main!(benches);
