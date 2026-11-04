use std::error::Error;
use std::process::Command;

#[test]
fn version_flag_reports_the_engine_identity() -> Result<(), Box<dyn Error>> {
    let output = Command::new(env!("CARGO_BIN_EXE_signal-lab-engine"))
        .arg("--version")
        .output()?;

    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout)?;
    assert_eq!(
        stdout.trim(),
        format!("signal-lab-engine {}", env!("CARGO_PKG_VERSION"))
    );

    Ok(())
}
