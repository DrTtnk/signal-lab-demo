use std::f64::consts::TAU;
use std::sync::Arc;

use rustfft::{Fft, FftPlannerScalar, num_complex::Complex};

use crate::contracts::{ChannelResult, Peak, Profile};

/// The immutable parts of the spectral analysis. They are shared by all workers.
pub struct Spectrum {
    fft: Arc<dyn Fft<f64>>,
    window: Vec<f64>,
    density_scale: f64,
    pcm_divisor: f64,
}

/// The FFT buffers of one worker.
pub struct Workspace {
    buffer: Vec<Complex<f64>>,
    scratch: Vec<Complex<f64>>,
}

/// One window of one channel: its samples in, its density terms and square sum out.
pub struct Window {
    pub samples: Vec<i16>,
    density: Vec<f64>,
    square_sum: u64,
}

impl Window {
    pub fn new(frames: usize) -> Self {
        Self {
            samples: vec![0; frames],
            density: vec![0.0; frames / 2 + 1],
            square_sum: 0,
        }
    }
}

/// The sums of one selected channel over all windows.
pub struct Channel {
    square_sum: u64,
    density_sum: Vec<f64>,
    index: usize,
}

impl Channel {
    pub fn new(index: usize, frames: usize) -> Self {
        Self {
            square_sum: 0,
            density_sum: vec![0.0; frames / 2 + 1],
            index,
        }
    }

    /// Adds one analysed window. Callers add the windows in window order, so the sums do not
    /// depend on how many workers analysed them.
    pub fn add(&mut self, window: &Window) {
        self.square_sum += window.square_sum;
        for (sum, term) in self.density_sum.iter_mut().zip(&window.density) {
            *sum += term;
        }
    }

    pub fn finish(self, windows: u64, profile: &Profile) -> ChannelResult {
        let mean_psd = self
            .density_sum
            .into_iter()
            .map(|sum| sum / windows as f64)
            .collect::<Vec<_>>();
        let bin_hz = f64::from(profile.sample_rate_hz) / profile.window_frames as f64;
        let band_powers = profile
            .bands
            .map(|[start, end]| mean_psd[start..end].iter().sum::<f64>() * bin_hz);
        let peak = select_peak(&mean_psd, bin_hz);
        ChannelResult {
            channel_index: self.index,
            rms: (self.square_sum as f64
                / (windows as f64 * profile.window_frames as f64)
                / profile.pcm_divisor.powi(2))
            .sqrt(),
            mean_psd,
            band_powers,
            peak,
        }
    }
}

impl Spectrum {
    pub fn new(profile: &Profile) -> Self {
        let size = profile.window_frames;
        let fft = FftPlannerScalar::new().plan_fft_forward(size);
        let window = (0..size)
            .map(|index| (1.0 - (TAU * index as f64 / size as f64).cos()) / 2.0)
            .collect::<Vec<_>>();
        let energy = window.iter().map(|value| value * value).sum::<f64>();
        Self {
            fft,
            window,
            density_scale: 1.0 / (f64::from(profile.sample_rate_hz) * energy),
            pcm_divisor: profile.pcm_divisor,
        }
    }

    pub fn workspace(&self) -> Workspace {
        Workspace {
            buffer: vec![Complex::default(); self.window.len()],
            scratch: vec![Complex::default(); self.fft.get_inplace_scratch_len()],
        }
    }

    /// Fills the density terms and the square sum of the samples of this window.
    pub fn analyse(&self, workspace: &mut Workspace, window: &mut Window) {
        window.square_sum = 0;
        for ((slot, sample), weight) in workspace
            .buffer
            .iter_mut()
            .zip(&window.samples)
            .zip(&self.window)
        {
            let integer = u64::from(i32::from(*sample).unsigned_abs());
            window.square_sum += integer * integer;
            *slot = Complex::new(f64::from(*sample) / self.pcm_divisor * weight, 0.0);
        }
        self.fft
            .process_with_scratch(&mut workspace.buffer, &mut workspace.scratch);
        let last = window.density.len() - 1;
        for (index, term) in window.density.iter_mut().enumerate() {
            let weight = if index == 0 || index == last {
                1.0
            } else {
                2.0
            };
            *term = workspace.buffer[index].norm_sqr() * self.density_scale * weight;
        }
    }
}

pub fn select_peak(psd: &[f64], bin_hz: f64) -> Peak {
    let mut maximum = 0.0;
    let mut peak = Peak::NoPeak;
    for (bin, power) in psd.iter().enumerate() {
        if *power > maximum {
            maximum = *power;
            peak = Peak::Peak {
                bin,
                frequency_hz: bin as f64 * bin_hz,
            };
        }
    }
    peak
}

#[cfg(test)]
mod tests {
    use super::select_peak;
    use crate::contracts::Peak;

    #[test]
    fn exact_ties_select_the_first_bin() {
        assert_eq!(
            select_peak(&[0.0, 2.0, 2.0, 0.0], 0.5),
            Peak::Peak {
                bin: 1,
                frequency_hz: 0.5
            }
        );
        assert_eq!(select_peak(&[0.0; 3], 0.5), Peak::NoPeak);
    }

    #[test]
    fn pcm16_square_sum_fits_for_the_entire_riff_frame_range() {
        let worst = u64::from(u32::MAX) * (1_u64 << 30);
        assert!(worst < u64::MAX);
    }
}
