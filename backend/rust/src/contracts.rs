use serde::{Deserialize, Serialize};
use serde_json::Value;

const SCHEMA: &str = include_str!("../../../contracts/v1/schema.json");
const PROFILE: &str = include_str!("../../../contracts/v1/profile.json");

pub fn validator(definition: &str) -> Result<jsonschema::Validator, Box<dyn std::error::Error>> {
    let mut schema: Value = serde_json::from_str(SCHEMA)?;
    schema["$ref"] = Value::String(format!("#/$defs/{definition}"));
    Ok(jsonschema::options()
        .with_draft(jsonschema::Draft::Draft202012)
        .should_validate_formats(true)
        .build(&schema)?)
}

fn canonical_integers(value: &mut Value) -> Result<(), String> {
    match value {
        Value::Number(number) if !number.is_u64() => {
            let float = number
                .as_f64()
                .ok_or("Invalid JSON integer representation")?;
            *number = (float as u64).into();
        }
        Value::Array(values) => {
            for value in values {
                canonical_integers(value)?;
            }
        }
        Value::Object(values) => {
            for value in values.values_mut() {
                canonical_integers(value)?;
            }
        }
        _ => {}
    }
    Ok(())
}

pub fn parse_input(bytes: &[u8], schema: &jsonschema::Validator) -> Result<Input, String> {
    let mut value: Value = serde_json::from_slice(bytes).map_err(|error| error.to_string())?;
    schema.validate(&value).map_err(|error| {
        format!(
            "Invalid EngineInput at {}: {:?}",
            error.instance_path(),
            error.kind()
        )
    })?;
    canonical_integers(&mut value)?;
    serde_json::from_value(value).map_err(|error| error.to_string())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Input {
    pub profile_id: String,
    pub item_id: String,
    pub recording: Recording,
    pub channels: Vec<usize>,
    pub workers: u8,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Recording {
    pub recording_id: String,
    pub sha256: String,
    pub byte_length: u64,
    pub frame_count: u64,
    pub channel_count: u16,
    pub provenance: Provenance,
}

#[derive(Deserialize, Serialize)]
#[serde(tag = "kind")]
pub enum Provenance {
    #[serde(rename_all = "camelCase")]
    FixedPattern {
        recipe_version: String,
        pattern_name: Pattern,
    },
    #[serde(rename_all = "camelCase")]
    Seeded { recipe_version: String, seed: u64 },
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum Pattern {
    ZeroMono,
    DcMono,
    NyquistMono,
    QuarterCycleMono,
    StereoWithTail,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    pub id: String,
    pub sample_rate_hz: u32,
    pub window_frames: usize,
    pub pcm_bits: u16,
    pub pcm_divisor: f64,
    pub bands: [[usize; 2]; 3],
}

impl Profile {
    pub fn load() -> Result<Self, Box<dyn std::error::Error>> {
        let value: Value = serde_json::from_str(PROFILE)?;
        validator("Profile")?
            .validate(&value)
            .map_err(|error| error.to_string())?;
        Ok(serde_json::from_value(value)?)
    }
}

#[derive(Serialize, Debug, PartialEq)]
#[serde(tag = "kind")]
pub enum Peak {
    NoPeak,
    #[serde(rename_all = "camelCase")]
    Peak {
        bin: usize,
        frequency_hz: f64,
    },
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelResult {
    pub channel_index: usize,
    pub rms: f64,
    pub mean_psd: Vec<f64>,
    pub band_powers: [f64; 3],
    pub peak: Peak,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalysisResult {
    pub processed_frames: u64,
    pub ignored_tail_frames: u64,
    pub processed_windows: u64,
    pub channels: Vec<ChannelResult>,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "snake_case")]
pub enum ItemCode {
    ChannelUnavailable,
    UnsupportedRecording,
    TruncatedRecording,
    DigestMismatch,
    InsufficientFrames,
}

#[derive(Serialize, Debug, thiserror::Error)]
#[error("{message}")]
pub struct Refusal {
    pub code: ItemCode,
    pub message: &'static str,
}

impl Refusal {
    pub fn new(code: ItemCode, message: &'static str) -> Self {
        Self { code, message }
    }
}

#[derive(Serialize)]
#[serde(tag = "state")]
pub enum Output {
    Succeeded(Success),
    Failed(Failure),
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Success {
    pub item_id: String,
    pub recording_id: String,
    pub recording_sha256: String,
    pub recording_provenance: Provenance,
    pub profile_id: String,
    pub engine_build: &'static str,
    pub workers: u8,
    pub result: AnalysisResult,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Failure {
    pub item_id: String,
    pub recording_id: String,
    pub error: Refusal,
}
