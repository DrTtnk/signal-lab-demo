#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v recordings_password="$RECORDINGS_DB_PASSWORD" \
  -v analysis_password="$ANALYSIS_DB_PASSWORD" <<'SQL'
CREATE ROLE recordings LOGIN PASSWORD :'recordings_password';
CREATE ROLE analysis LOGIN PASSWORD :'analysis_password';
CREATE SCHEMA recordings AUTHORIZATION recordings;
CREATE SCHEMA analysis AUTHORIZATION analysis;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SQL
