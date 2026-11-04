use std::io::{self, BufRead, Read, Write};
use std::process::ExitCode;

use clap::{Parser, Subcommand};
use signal_lab_engine::contracts::{self, Failure, Output, Profile, Success};
use signal_lab_engine::engine::{self, EngineError};

const CONTROL_LIMIT: u64 = 64 * 1024;

#[derive(Parser)]
#[command(version, about = "Signal Lab scalar PCM16 analysis engine")]
struct Arguments {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Read one EngineInput JSON line, then WAV bytes and EOF, from stdin.
    Analyse,
}

#[derive(Debug, thiserror::Error)]
enum Error {
    #[error("{0}")]
    Control(String),
    #[error(transparent)]
    Runtime(#[from] Box<dyn std::error::Error>),
}

fn run() -> Result<ExitCode, Error> {
    let arguments = Arguments::parse();
    let Command::Analyse = arguments.command;
    let schema = contracts::validator("EngineInput")?;
    let profile = Profile::load()?;
    let mut source = io::stdin().lock();
    let mut header = Vec::new();
    source
        .by_ref()
        .take(CONTROL_LIMIT + 1)
        .read_until(b'\n', &mut header)
        .map_err(|error| Error::Runtime(error.into()))?;
    if header.len() as u64 > CONTROL_LIMIT || header.last() != Some(&b'\n') {
        return Err(Error::Control(
            "EngineInput must end with LF within 64 KiB".into(),
        ));
    }
    let input = contracts::parse_input(&header, &schema).map_err(Error::Control)?;
    if input.profile_id != profile.id {
        return Err(Error::Control(
            "Input and compiled profile do not match".into(),
        ));
    }
    let (output, status) = match engine::analyse(source, &input, &profile) {
        Ok(result) => (
            Output::Succeeded(Success {
                item_id: input.item_id,
                recording_id: input.recording.recording_id,
                recording_sha256: input.recording.sha256,
                recording_provenance: input.recording.provenance,
                profile_id: input.profile_id,
                engine_build: concat!(env!("CARGO_PKG_VERSION"), "+scalar-fft-parallel-windows-v1"),
                workers: input.workers,
                result,
            }),
            ExitCode::SUCCESS,
        ),
        Err(EngineError::Refused(error)) => (
            Output::Failed(Failure {
                item_id: input.item_id,
                recording_id: input.recording.recording_id,
                error,
            }),
            ExitCode::from(2),
        ),
        Err(error) => return Err(Error::Runtime(error.into())),
    };
    let mut output_stream = io::stdout().lock();
    serde_json::to_writer(&mut output_stream, &output)
        .map_err(|error| Error::Runtime(error.into()))?;
    output_stream
        .write_all(b"\n")
        .map_err(|error| Error::Runtime(error.into()))?;
    output_stream
        .flush()
        .map_err(|error| Error::Runtime(error.into()))?;
    Ok(status)
}

fn main() -> ExitCode {
    match run() {
        Ok(status) => status,
        Err(error) => {
            eprintln!("{error}");
            match error {
                Error::Control(_) => ExitCode::from(64),
                Error::Runtime(_) => ExitCode::from(70),
            }
        }
    }
}
