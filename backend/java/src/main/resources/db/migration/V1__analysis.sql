CREATE TABLE analysis.batch (
  batch_id uuid PRIMARY KEY,
  profile_id text NOT NULL CHECK (profile_id = 'pcm16-48k-hann4096-v1'),
  state text NOT NULL CHECK (
    state IN ('Running', 'Completed', 'CompletedWithErrors', 'AllItemsFailed', 'Interrupted')),
  created_at timestamptz NOT NULL,
  finished_at timestamptz,
  error jsonb,
  CONSTRAINT finished_exactly_when_not_running CHECK ((state = 'Running') = (finished_at IS NULL)),
  CONSTRAINT error_exactly_when_interrupted CHECK ((state = 'Interrupted') = (error IS NOT NULL))
);

CREATE TABLE analysis.item (
  item_id uuid PRIMARY KEY,
  batch_id uuid NOT NULL REFERENCES analysis.batch (batch_id),
  position integer NOT NULL CHECK (position >= 0),
  recording_id uuid NOT NULL,
  channels smallint[] NOT NULL CHECK (cardinality(channels) BETWEEN 1 AND 4),
  recording_snapshot jsonb,
  state text NOT NULL CHECK (
    state IN ('Pending', 'Running', 'Succeeded', 'Failed', 'Interrupted', 'NotRun')),
  result jsonb,
  error jsonb,
  engine_build text,
  workers smallint CHECK (workers IN (1, 2, 4)),
  UNIQUE (batch_id, position),
  CONSTRAINT snapshot_absent_only_for_unknown_recording CHECK (
    recording_snapshot IS NOT NULL OR (state = 'Failed' AND error ->> 'code' = 'unknown_recording')),
  CONSTRAINT outcome_matches_state CHECK (
    CASE state
      WHEN 'Succeeded' THEN
        result IS NOT NULL AND error IS NULL AND engine_build IS NOT NULL AND workers IS NOT NULL
      WHEN 'Failed' THEN
        error IS NOT NULL AND result IS NULL AND engine_build IS NULL AND workers IS NULL
      WHEN 'Interrupted' THEN
        error IS NOT NULL AND result IS NULL AND engine_build IS NULL AND workers IS NULL
      ELSE
        result IS NULL AND error IS NULL AND engine_build IS NULL AND workers IS NULL
    END)
);
