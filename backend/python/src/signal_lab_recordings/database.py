from pathlib import Path

import sqlalchemy as sa
from alembic import command
from alembic.config import Config
from sqlalchemy.dialects import postgresql
from sqlalchemy.engine import Engine

recording = sa.Table(
    "recording",
    sa.MetaData(),
    sa.Column("recording_id", postgresql.UUID(as_uuid=True), primary_key=True),
    sa.Column("name", sa.Text),
    sa.Column("sha256", sa.String(64)),
    sa.Column("byte_length", sa.BigInteger),
    sa.Column("frame_count", sa.BigInteger),
    sa.Column("channel_count", sa.SmallInteger),
    sa.Column("sample_rate_hz", sa.Integer),
    sa.Column("encoding", sa.Text),
    sa.Column("provenance", postgresql.JSONB),
    schema="recordings",
)


def migrate(engine: Engine) -> None:
    config = Config()
    config.set_main_option("script_location", str(Path(__file__).parent / "migrations"))
    with engine.begin() as connection:
        config.attributes["connection"] = connection
        command.upgrade(config, "head")
