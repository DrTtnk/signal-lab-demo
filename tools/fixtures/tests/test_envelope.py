import math

import numpy as np
import pytest

from signal_lab_fixtures.envelope import (
    Machine,
    ceiling,
    counted_fft,
    fft_flops_per_sample_range,
)


@pytest.mark.parametrize("size", [2, 8, 64, 4096])
def test_the_counted_radix2_fft_is_an_fft_that_costs_five_n_log_n(size: int) -> None:
    rng = np.random.default_rng(size)
    signal = rng.standard_normal(size) + 1j * rng.standard_normal(size)

    result, flops = counted_fft(signal.astype(np.complex128))

    np.testing.assert_allclose(result, np.fft.fft(signal), rtol=1e-9, atol=1e-9)
    assert flops == 5 * size * round(math.log2(size))


def test_the_flops_per_sample_range_for_the_profile_window() -> None:
    low, high = fft_flops_per_sample_range(4096)

    assert high == 60.0
    assert math.isclose(low, 34 / 9 * 12)


def test_the_ceiling_is_the_smaller_of_execution_and_transfer() -> None:
    machine = Machine(
        clock_hz=1e9,
        flops_per_cycle=2.0,
        cores=1,
        dram_bytes_per_second=1e9,
    )

    execution_bound = ceiling(machine, flops_per_sample=50.0, dram_bytes_per_sample=2.0)
    assert math.isclose(execution_bound.execution, 40e6)
    assert math.isclose(execution_bound.transfer, 500e6)
    assert math.isclose(execution_bound.samples_per_second, 40e6)
    assert execution_bound.bound == "execution"

    slow_memory = Machine(clock_hz=1e9, flops_per_cycle=2.0, cores=1, dram_bytes_per_second=1e6)
    transfer_bound = ceiling(slow_memory, flops_per_sample=50.0, dram_bytes_per_sample=2.0)
    assert math.isclose(transfer_bound.samples_per_second, 5e5)
    assert transfer_bound.bound == "transfer"


def test_four_cores_multiply_the_execution_ceiling() -> None:
    one = Machine(clock_hz=1e9, flops_per_cycle=2.0, cores=1, dram_bytes_per_second=1e12)
    four = Machine(clock_hz=1e9, flops_per_cycle=2.0, cores=4, dram_bytes_per_second=1e12)

    assert math.isclose(ceiling(four, 50.0, 2.0).execution, 4 * ceiling(one, 50.0, 2.0).execution)
