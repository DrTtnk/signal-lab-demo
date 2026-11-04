import { useEffect, useState } from "react";
import {
  POLL_INTERVAL_MS,
  buildRequest,
  fetchBatch,
  isTerminal,
  submitAnalysis,
  type AnalysisOutcome,
  type Selection,
} from "./analysis";
import { RecordingTable } from "./RecordingTable";
import { fetchRecordings, type RecordingsOutcome } from "./recordings";
import { ResultView } from "./ResultView";

type Catalogue = { status: "loading" } | RecordingsOutcome;
type Analysis =
  | { status: "idle" }
  | { status: "running" }
  | AnalysisOutcome
  | { status: "failed"; code: string; message: string };

function describe(error: unknown): string {
  return error instanceof Error ? error.message : "Unknown failure";
}

export function App() {
  const [catalogue, setCatalogue] = useState<Catalogue>({ status: "loading" });
  const [selections, setSelections] = useState<readonly Selection[]>([]);
  const [analysis, setAnalysis] = useState<Analysis>({ status: "idle" });

  useEffect(() => {
    fetchRecordings()
      .then(setCatalogue)
      .catch((error: unknown) => {
        setCatalogue({
          status: "failed",
          code: "invalid_response",
          message: describe(error),
        });
      });
  }, []);

  useEffect(() => {
    if (analysis.status !== "batch" || isTerminal(analysis.batch)) {
      return;
    }
    const timer = setTimeout(() => {
      fetchBatch(analysis.batch.batchId)
        .then((batch) => {
          setAnalysis({ status: "batch", batch });
        })
        .catch((error: unknown) => {
          setAnalysis({
            status: "failed",
            code: "invalid_response",
            message: describe(error),
          });
        });
    }, POLL_INTERVAL_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [analysis]);

  const waiting =
    analysis.status === "running" ||
    (analysis.status === "batch" && !isTerminal(analysis.batch));

  const analyse = () => {
    setAnalysis({ status: "running" });
    submitAnalysis(buildRequest(selections, () => crypto.randomUUID()))
      .then(setAnalysis)
      .catch((error: unknown) => {
        setAnalysis({
          status: "failed",
          code: "invalid_response",
          message: describe(error),
        });
      });
  };

  const names = new Map(
    catalogue.status === "ready"
      ? catalogue.recordings.map((recording) => [
          recording.recordingId,
          recording.name,
        ])
      : [],
  );

  return (
    <main>
      <h1>Signal Lab</h1>
      {catalogue.status === "loading" && (
        <p role="status">Loading recordings</p>
      )}
      {catalogue.status === "failed" && (
        <p role="alert">
          {catalogue.code}: {catalogue.message}
        </p>
      )}
      {catalogue.status === "ready" && (
        <>
          <RecordingTable
            recordings={catalogue.recordings}
            selections={selections}
            onChange={setSelections}
          />
          <button
            type="button"
            disabled={selections.length === 0 || waiting}
            onClick={analyse}
          >
            Analyse selected
          </button>
        </>
      )}
      {waiting && <p role="status">Analysing, please wait</p>}
      {analysis.status === "failed" && (
        <p role="alert">
          {analysis.code}: {analysis.message}
        </p>
      )}
      {analysis.status === "batch" && (
        <ResultView batch={analysis.batch} names={names} />
      )}
    </main>
  );
}
