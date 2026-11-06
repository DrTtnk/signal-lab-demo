import { RecordingTable } from "./RecordingTable";
import { ResultView } from "./ResultView";
import { useAnalysis } from "./useAnalysis";

export function App() {
  const {
    catalogue,
    selections,
    setSelections,
    analysis,
    waiting,
    analyse,
    names,
  } = useAnalysis("", () => crypto.randomUUID());

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
