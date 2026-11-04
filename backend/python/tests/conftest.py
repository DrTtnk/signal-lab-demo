from collections.abc import Iterator
from pathlib import Path

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine
from testcontainers.community.postgres import PostgresContainer

from signal_lab_recordings.settings import Settings

ROOT = Path(__file__).resolve().parents[3]
INIT_SCRIPT = ROOT / "docker" / "database" / "init.sh"
RECORDINGS_PASSWORD = "recordings-test-only"
ANALYSIS_PASSWORD = "analysis-test-only"


@pytest.fixture(scope="session")
def database() -> Iterator[PostgresContainer]:
    container = (
        PostgresContainer("postgres:18.1-alpine", username="postgres", dbname="signal_lab")
        .with_env("RECORDINGS_DB_PASSWORD", RECORDINGS_PASSWORD)
        .with_env("ANALYSIS_DB_PASSWORD", ANALYSIS_PASSWORD)
        .with_volume_mapping(str(INIT_SCRIPT), "/docker-entrypoint-initdb.d/init.sh", "ro")
    )
    with container:
        yield container


def role_url(database: PostgresContainer, role: str, password: str) -> str:
    host = database.get_container_host_ip()
    port = database.get_exposed_port(5432)
    return f"postgresql+psycopg://{role}:{password}@{host}:{port}/signal_lab"


@pytest.fixture
def settings(database: PostgresContainer, tmp_path: Path) -> Iterator[Settings]:
    url = role_url(database, "recordings", RECORDINGS_PASSWORD)
    administrator = create_engine(database.get_connection_url(driver="psycopg"))
    with administrator.begin() as connection:
        connection.execute(text("DROP SCHEMA recordings CASCADE"))
        connection.execute(text("CREATE SCHEMA recordings AUTHORIZATION recordings"))
    administrator.dispose()
    yield Settings(
        database_url=url,
        recordings_dir=tmp_path,
        contracts_dir=ROOT / "contracts" / "v1",
    )


@pytest.fixture
def engine(settings: Settings) -> Iterator[Engine]:
    created = create_engine(settings.database_url)
    yield created
    created.dispose()
