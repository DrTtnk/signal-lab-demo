import type { Batch, BatchItem } from "./analysis";
import { BAND_EDGES_HZ, format, spectrum, type Channel } from "./result-format";

function Spectrum({ channel }: { channel: Channel }) {
  const { width, height, points } = spectrum(channel);
  return (
    <svg
      role="img"
      aria-label={`Spectrum of channel ${String(channel.channelIndex)}, 0 to 24000 Hz, logarithmic power density`}
      viewBox={`0 0 ${String(width)} ${String(height)}`}
      width="100%"
    >
      <polyline points={points} fill="none" stroke="#60a5fa" strokeWidth="1" />
      <text x="0" y={height - 4} fill="#9ca3af" fontSize="10">
        0 Hz
      </text>
      <text
        x={width}
        y={height - 4}
        fill="#9ca3af"
        fontSize="10"
        textAnchor="end"
      >
        24000 Hz
      </text>
    </svg>
  );
}

function ChannelView({ channel }: { channel: Channel }) {
  return (
    <section aria-label={`Channel ${String(channel.channelIndex)}`}>
      <h4>Channel {channel.channelIndex}</h4>
      <p>RMS {format(channel.rms)} (normalised amplitude)</p>
      <p>
        {channel.peak.kind === "Peak"
          ? `Peak ${String(channel.peak.frequencyHz)} Hz (bin ${String(channel.peak.bin)})`
          : "No spectral peak"}
      </p>
      <ul>
        {channel.bandPowers.map((power, index) => (
          <li key={BAND_EDGES_HZ[index]}>
            {`Band ${String(index + 1)} (${String(BAND_EDGES_HZ[index])}–${String(BAND_EDGES_HZ[index + 1])} Hz): ${format(power)} normalised amplitude²`}
          </li>
        ))}
      </ul>
      <Spectrum channel={channel} />
    </section>
  );
}

function ItemView({ item, name }: { item: BatchItem; name: string }) {
  switch (item.state) {
    case "Succeeded": {
      const { result } = item;
      return (
        <section aria-label={name}>
          <h3>{name}</h3>
          <p>
            {`${String(result.processedWindows)} windows, ${String(result.processedFrames)} frames, ${String(result.ignoredTailFrames)} frames ignored`}
          </p>
          {result.channels.map((channel) => (
            <ChannelView key={channel.channelIndex} channel={channel} />
          ))}
        </section>
      );
    }
    case "Failed":
    case "Interrupted":
      return (
        <section aria-label={name}>
          <h3>{name}</h3>
          <p role="alert">
            {item.error.code}: {item.error.message}
          </p>
        </section>
      );
    case "NotRun":
      return (
        <section aria-label={name}>
          <h3>{name}</h3>
          <p>Not run: batch interrupted</p>
        </section>
      );
    case "Pending":
    case "Running":
      return (
        <section aria-label={name}>
          <h3>{name}</h3>
          <p>{item.state}</p>
        </section>
      );
  }
}

export function ResultView({
  batch,
  names,
}: {
  batch: Batch;
  names: ReadonlyMap<string, string>;
}) {
  return (
    <section aria-label="Analysis result">
      <h2>Analysis {batch.state}</h2>
      <p>Batch {batch.batchId}</p>
      {batch.state === "Interrupted" && (
        <p role="alert">
          {batch.error.code}: {batch.error.message}
        </p>
      )}
      {batch.items.map((item) => (
        <ItemView
          key={item.itemId}
          item={item}
          name={names.get(item.recordingId) ?? item.recordingId}
        />
      ))}
    </section>
  );
}
