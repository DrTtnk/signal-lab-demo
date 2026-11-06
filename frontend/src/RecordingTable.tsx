import { kibibytes, range, selectChannels } from "./selection";
import type { Selection } from "./analysis";
import type { Recording } from "./recordings";

export function RecordingTable({
  recordings,
  selections,
  onChange,
}: {
  recordings: readonly Recording[];
  selections: readonly Selection[];
  onChange: (selections: readonly Selection[]) => void;
}) {
  if (recordings.length === 0) {
    return <p>No recordings are registered.</p>;
  }

  const channelsOf = (recordingId: string): readonly number[] | undefined =>
    selections.find((selection) => selection.recordingId === recordingId)
      ?.channels;

  const setChannels = (recordingId: string, channels: readonly number[]) => {
    onChange(selectChannels(selections, recordingId, channels));
  };

  return (
    <table>
      <caption>Registered recordings</caption>
      <thead>
        <tr>
          <th scope="col">Analyse</th>
          <th scope="col">Name</th>
          <th scope="col">Channels</th>
          <th scope="col">Sample rate</th>
          <th scope="col">Frames</th>
          <th scope="col">Size</th>
        </tr>
      </thead>
      <tbody>
        {recordings.map((recording) => {
          const chosen = channelsOf(recording.recordingId);
          return (
            <tr key={recording.recordingId}>
              <td>
                <input
                  type="checkbox"
                  aria-label={`Analyse ${recording.name}`}
                  checked={chosen !== undefined}
                  onChange={(event) => {
                    setChannels(
                      recording.recordingId,
                      event.target.checked ? range(recording.channelCount) : [],
                    );
                  }}
                />
              </td>
              <th scope="row">{recording.name}</th>
              <td>
                {range(recording.channelCount).map((channel) => (
                  <span key={channel} className="channel">
                    <input
                      type="checkbox"
                      aria-label={`Channel ${String(channel)} of ${recording.name}`}
                      disabled={chosen === undefined}
                      checked={chosen?.includes(channel) ?? false}
                      onChange={(event) => {
                        const current = chosen ?? [];
                        setChannels(
                          recording.recordingId,
                          event.target.checked
                            ? [...current, channel]
                            : current.filter((value) => value !== channel),
                        );
                      }}
                    />
                    {channel}
                  </span>
                ))}
              </td>
              <td>{recording.sampleRateHz / 1000} kHz</td>
              <td>{recording.frameCount}</td>
              <td>{kibibytes(recording.byteLength)}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
