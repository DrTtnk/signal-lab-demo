import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0001"
down_revision = None


def upgrade() -> None:
    op.create_table(
        "recording",
        sa.Column("recording_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("name", sa.Text, nullable=False),
        sa.Column("sha256", sa.String(64), nullable=False, unique=True),
        sa.Column("byte_length", sa.BigInteger, nullable=False),
        sa.Column("frame_count", sa.BigInteger, nullable=False),
        sa.Column("channel_count", sa.SmallInteger, nullable=False),
        sa.Column("sample_rate_hz", sa.Integer, nullable=False),
        sa.Column("encoding", sa.Text, nullable=False),
        sa.Column("provenance", postgresql.JSONB, nullable=False),
        sa.CheckConstraint("char_length(name) BETWEEN 1 AND 80", name="name_length"),
        sa.CheckConstraint("sha256 ~ '^[0-9a-f]{64}$'", name="sha256_format"),
        sa.CheckConstraint("byte_length >= 1", name="byte_length_positive"),
        sa.CheckConstraint("frame_count >= 0", name="frame_count_not_negative"),
        sa.CheckConstraint("channel_count BETWEEN 1 AND 4", name="channel_count_range"),
        sa.CheckConstraint("sample_rate_hz = 48000", name="sample_rate_profile"),
        sa.CheckConstraint("encoding = 'wav_pcm_s16le'", name="encoding_profile"),
        sa.CheckConstraint("provenance->>'kind' IN ('FixedPattern', 'Seeded')", name="provenance_kind"),
        schema="recordings",
    )
