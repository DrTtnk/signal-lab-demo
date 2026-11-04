from collections.abc import AsyncGenerator, Sequence
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from sqlalchemy import Row, create_engine, select

from signal_lab_recordings.catalogue import register
from signal_lab_recordings.database import migrate, recording
from signal_lab_recordings.models import HealthResponse, Recording, RecordingId
from signal_lab_recordings.settings import Settings


def build_app(settings: Settings) -> FastAPI:
    engine = create_engine(settings.database_url)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncGenerator[None]:
        migrate(engine)
        register(engine, settings)
        yield
        engine.dispose()

    app = FastAPI(title="Signal Lab recordings", version="0.1.0", lifespan=lifespan)

    def find(recording_id: RecordingId) -> Row[tuple[object, ...]]:
        with engine.connect() as connection:
            row = connection.execute(select(recording).where(recording.c.recording_id == recording_id)).one_or_none()
        if row is None:
            raise HTTPException(status_code=404, detail="Unknown recording")
        return row

    @app.get("/health", response_model=HealthResponse)
    def health() -> HealthResponse:
        return HealthResponse(status="ready")

    @app.get("/recordings", response_model=list[Recording])
    def list_recordings() -> Sequence[Row[tuple[object, ...]]]:
        with engine.connect() as connection:
            return connection.execute(select(recording).order_by(recording.c.name)).all()

    @app.get("/recordings/{recording_id}", response_model=Recording)
    def get_recording(recording_id: RecordingId) -> Row[tuple[object, ...]]:
        return find(recording_id)

    @app.get("/recordings/{recording_id}/content")
    def get_content(recording_id: RecordingId) -> FileResponse:
        found = Recording.model_validate(find(recording_id))
        return FileResponse(settings.recordings_dir / f"{found.recording_id}.wav", media_type="audio/wav")

    return app


def create_app() -> FastAPI:
    return build_app(Settings.from_env())
