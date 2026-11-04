FROM ghcr.io/astral-sh/uv:0.8.15 AS uv
FROM python:3.13.7-slim-bookworm
COPY --from=uv /uv /uvx /bin/
WORKDIR /app
ENV UV_PROJECT_ENVIRONMENT=/app/.venv UV_CACHE_DIR=/tmp/uv-cache
COPY backend/python/pyproject.toml backend/python/uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY backend/python/src ./src
COPY contracts/v1 ./contracts/v1
ENV PATH=/app/.venv/bin:$PATH PYTHONPATH=/app/src
USER 10001:10001
EXPOSE 8000
CMD ["uvicorn", "--factory", "signal_lab_recordings.app:create_app", "--host", "0.0.0.0", "--port", "8000"]
