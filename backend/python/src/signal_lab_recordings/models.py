from typing import Annotated, Literal, NewType
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints
from pydantic.alias_generators import to_camel

RecordingId = NewType("RecordingId", UUID)
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]


class Strict(BaseModel):
    model_config = ConfigDict(
        strict=True,
        frozen=True,
        extra="forbid",
        alias_generator=to_camel,
        populate_by_name=True,
        from_attributes=True,
    )


class FixedPattern(Strict):
    kind: Literal["FixedPattern"]
    recipe_version: str
    pattern_name: str


class Seeded(Strict):
    kind: Literal["Seeded"]
    recipe_version: str
    seed: int


class Recording(Strict):
    recording_id: RecordingId
    name: str
    sha256: Sha256
    byte_length: int
    frame_count: int
    channel_count: int
    sample_rate_hz: int
    encoding: str
    provenance: Annotated[FixedPattern | Seeded, Field(discriminator="kind")]


class Manifest(Strict):
    contract_version: Literal["1"]
    recordings: list[Recording]


class HealthResponse(Strict):
    status: str
