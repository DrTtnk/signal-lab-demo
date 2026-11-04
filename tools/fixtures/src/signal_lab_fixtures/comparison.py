from typing import Literal

import numpy as np
from numpy.typing import NDArray
from pydantic import BaseModel, ConfigDict, Field, TypeAdapter

from signal_lab_fixtures.contracts import CONTRACT_ROOT

Metric = Literal["rms", "meanPsd", "bandPowers", "frequencyHz"]


class Tolerance(BaseModel):
    model_config = ConfigDict(strict=True, frozen=True, extra="forbid", allow_inf_nan=False)

    rtol: float = Field(ge=0)
    atol: float = Field(ge=0)


def assert_close(
    metric: Metric,
    actual: float | NDArray[np.float64],
    expected: float | NDArray[np.float64],
) -> None:
    policy = TypeAdapter(dict[Metric, Tolerance]).validate_json((CONTRACT_ROOT / "tolerances.json").read_text())
    tolerance = policy[metric]
    np.testing.assert_allclose(
        actual,
        expected,
        rtol=tolerance.rtol,
        atol=tolerance.atol,
        equal_nan=False,
        strict=True,
    )
