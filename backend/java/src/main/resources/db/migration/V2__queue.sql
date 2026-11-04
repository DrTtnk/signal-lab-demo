-- A batch is Queued until one consumer claims it. claimed_by names that consumer (the pod).
ALTER TABLE analysis.batch ADD COLUMN claimed_by text;
UPDATE analysis.batch SET claimed_by = 'before-queue' WHERE state NOT IN ('Queued', 'Interrupted');

ALTER TABLE analysis.batch
  DROP CONSTRAINT batch_state_check,
  DROP CONSTRAINT finished_exactly_when_not_running,
  ADD CONSTRAINT batch_state_check CHECK (
    state IN ('Queued', 'Running', 'Completed', 'CompletedWithErrors', 'AllItemsFailed', 'Interrupted')),
  ADD CONSTRAINT finished_exactly_when_terminal CHECK (
    (state IN ('Queued', 'Running')) = (finished_at IS NULL)),
  ADD CONSTRAINT claimed_exactly_when_started CHECK (
    CASE state
      WHEN 'Queued' THEN claimed_by IS NULL
      WHEN 'Interrupted' THEN true
      ELSE claimed_by IS NOT NULL
    END);
