import math
from dataclasses import dataclass
from typing import Literal

import numpy as np
from numpy.typing import NDArray


def counted_fft(signal: NDArray[np.complex128]) -> tuple[NDArray[np.complex128], int]:
    size = len(signal)
    if size == 1:
        return signal.copy(), 0
    even, even_flops = counted_fft(signal[0::2])
    odd, odd_flops = counted_fft(signal[1::2])
    twiddle = np.exp(-2j * np.pi * np.arange(size // 2) / size).astype(np.complex128)
    shifted = twiddle * odd
    result = np.concatenate((even + shifted, even - shifted))
    return result, even_flops + odd_flops + 10 * (size // 2)


def fft_flops_per_sample_range(size: int) -> tuple[float, float]:
    log_size = math.log2(size)
    return 34 / 9 * log_size, 5 * log_size


@dataclass(frozen=True)
class Machine:
    clock_hz: float
    flops_per_cycle: float
    cores: int
    dram_bytes_per_second: float


@dataclass(frozen=True)
class Ceiling:
    execution: float
    transfer: float
    samples_per_second: float
    bound: Literal["execution", "transfer"]


def ceiling(machine: Machine, flops_per_sample: float, dram_bytes_per_sample: float) -> Ceiling:
    execution = machine.clock_hz * machine.flops_per_cycle * machine.cores / flops_per_sample
    transfer = machine.dram_bytes_per_second / dram_bytes_per_sample
    bound: Literal["execution", "transfer"] = "execution" if execution <= transfer else "transfer"
    return Ceiling(execution, transfer, min(execution, transfer), bound)


OTHER_FLOPS_PER_SAMPLE = 1 + 2 + (3 + 2 + 1) * 2049 / 4096

WINDOW_FRAMES = 4096
KERNEL_SAMPLES_PER_SECOND = 1 / 20.333e-6 * WINDOW_FRAMES
ENGINE_SAMPLES = 1024 * WINDOW_FRAMES * 4
ENGINE_SECONDS = {1: 142.59e-3, 2: 92.767e-3, 4: 55.004e-3}


def main() -> None:
    low, high = fft_flops_per_sample_range(WINDOW_FRAMES)
    totals = (low + OTHER_FLOPS_PER_SAMPLE, high + OTHER_FLOPS_PER_SAMPLE)
    print(f"flops per sample: FFT {low:.1f}-{high:.1f}, other {OTHER_FLOPS_PER_SAMPLE:.2f}")
    print(f"flops per sample in total: {totals[0]:.1f}-{totals[1]:.1f}")
    for flops_per_cycle in (4.0, 2.0):
        for cores in (1, 4):
            machine = Machine(5.3e9, flops_per_cycle, cores, 60e9)
            best = ceiling(machine, totals[0], 2.0)
            worst = ceiling(machine, totals[1], 2.0)
            print(
                f"{flops_per_cycle:.0f} flops/cycle, {cores} core(s): "
                f"execution {worst.execution / 1e6:.0f}-{best.execution / 1e6:.0f} Msamples/s, "
                f"transfer {best.transfer / 1e6:.0f} Msamples/s, bound {best.bound}"
            )
    machine_one = Machine(5.3e9, 4.0, 1, 60e9)
    machine_four = Machine(5.3e9, 4.0, 4, 60e9)
    kernel = KERNEL_SAMPLES_PER_SECOND
    low_ceiling = ceiling(machine_one, totals[1], 2.0).samples_per_second
    high_ceiling = ceiling(machine_one, totals[0], 2.0).samples_per_second
    print(
        f"kernel {kernel / 1e6:.1f} Msamples/s = "
        f"{kernel / high_ceiling:.0%}-{kernel / low_ceiling:.0%} of the one-core ceiling"
    )
    low_four = ceiling(machine_four, totals[1], 2.0).samples_per_second
    high_four = ceiling(machine_four, totals[0], 2.0).samples_per_second
    for workers, seconds in ENGINE_SECONDS.items():
        rate = ENGINE_SAMPLES / seconds
        print(f"engine, {workers} worker(s): {rate / 1e6:.1f} Msamples/s", end="")
        if workers == 4:
            print(f" = {rate / high_four:.0%}-{rate / low_four:.0%} of the four-core ceiling")
        else:
            print()


if __name__ == "__main__":
    main()
