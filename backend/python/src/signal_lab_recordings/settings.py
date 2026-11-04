import os
from pathlib import Path

from pydantic import BaseModel, ConfigDict


class Settings(BaseModel):
    model_config = ConfigDict(strict=True, frozen=True, extra="forbid")

    database_url: str
    recordings_dir: Path
    contracts_dir: Path

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            database_url=os.environ["RECORDINGS_DATABASE_URL"],
            recordings_dir=Path(os.environ["RECORDINGS_DIR"]),
            contracts_dir=Path(os.environ["CONTRACTS_DIR"]),
        )
