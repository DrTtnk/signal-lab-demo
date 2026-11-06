import { randomUUID } from "expo-crypto";
import {
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  useColorScheme,
  View,
} from "react-native";
import Svg, { Polyline, Text as SvgText } from "react-native-svg";
import type { BatchItem } from "../../frontend/src/analysis";
import {
  BAND_EDGES_HZ,
  format,
  spectrum,
  type Channel,
} from "../../frontend/src/result-format";
import { kibibytes, range, selectChannels } from "../../frontend/src/selection";
import { useAnalysis } from "../../frontend/src/useAnalysis";

function apiOrigin(): string {
  if (Platform.OS === "web") return "";
  const origin = process.env.EXPO_PUBLIC_API_ORIGIN;
  if (!origin)
    throw new Error("Set EXPO_PUBLIC_API_ORIGIN to the Signal Lab API origin");
  return origin.replace(/\/$/, "");
}

function ChannelView({
  channel,
  foreground,
}: {
  channel: Channel;
  foreground: string;
}) {
  const { width, height, points } = spectrum(channel);
  const label = `Spectrum of channel ${channel.channelIndex}, 0 to 24000 Hz, logarithmic power density`;
  return (
    <View style={styles.section}>
      <Text
        accessibilityRole="header"
        style={[styles.heading, { color: foreground }]}
      >
        Channel {channel.channelIndex}
      </Text>
      <Text style={{ color: foreground }}>
        RMS {format(channel.rms)} (normalised amplitude)
      </Text>
      <Text style={{ color: foreground }}>
        {channel.peak.kind === "Peak"
          ? `Peak ${channel.peak.frequencyHz} Hz (bin ${channel.peak.bin})`
          : "No spectral peak"}
      </Text>
      {channel.bandPowers.map((power, index) => (
        <Text key={index} style={{ color: foreground }}>
          {`• Band ${index + 1} (${BAND_EDGES_HZ[index]}–${BAND_EDGES_HZ[index + 1]} Hz): ${format(power)} normalised amplitude²`}
        </Text>
      ))}
      <View accessibilityRole="image" accessibilityLabel={label}>
        <Svg width="100%" height={height} viewBox={`0 0 ${width} ${height}`}>
          <Polyline
            points={points}
            fill="none"
            stroke="#60a5fa"
            strokeWidth={1}
          />
          <SvgText x={0} y={height - 4} fill="#9ca3af" fontSize={10}>
            0 Hz
          </SvgText>
          <SvgText
            x={width}
            y={height - 4}
            fill="#9ca3af"
            fontSize={10}
            textAnchor="end"
          >
            24000 Hz
          </SvgText>
        </Svg>
      </View>
    </View>
  );
}

function ItemView({
  item,
  name,
  foreground,
  errorColor,
}: {
  item: BatchItem;
  name: string;
  foreground: string;
  errorColor: string;
}) {
  return (
    <View style={styles.section}>
      <Text
        accessibilityRole="header"
        style={[styles.heading, { color: foreground }]}
      >
        {name}
      </Text>
      {item.state === "Succeeded" && (
        <>
          <Text style={{ color: foreground }}>
            {`${item.result.processedWindows} windows, ${item.result.processedFrames} frames, ${item.result.ignoredTailFrames} frames ignored`}
          </Text>
          {item.result.channels.map((channel) => (
            <ChannelView
              key={channel.channelIndex}
              channel={channel}
              foreground={foreground}
            />
          ))}
        </>
      )}
      {(item.state === "Failed" || item.state === "Interrupted") && (
        <Text accessibilityRole="alert" style={{ color: errorColor }}>
          {item.error.code}: {item.error.message}
        </Text>
      )}
      {item.state === "NotRun" && (
        <Text style={{ color: foreground }}>Not run: batch interrupted</Text>
      )}
      {(item.state === "Pending" || item.state === "Running") && (
        <Text style={{ color: foreground }}>{item.state}</Text>
      )}
    </View>
  );
}

function Check({
  label,
  checked,
  disabled = false,
  onPress,
  foreground,
  text,
}: {
  label: string;
  checked: boolean;
  disabled?: boolean;
  onPress: () => void;
  foreground: string;
  text: string;
}) {
  return (
    <Pressable
      accessibilityRole="checkbox"
      accessibilityLabel={label}
      aria-checked={checked}
      disabled={disabled}
      onPress={onPress}
      style={[styles.check, disabled && styles.disabled]}
    >
      <Text style={{ color: foreground }}>
        {checked ? "☑" : "☐"} {text}
      </Text>
    </Pressable>
  );
}

export function App() {
  const dark = useColorScheme() === "dark";
  const foreground = dark ? "#e5e7eb" : "#111827";
  const errorColor = dark ? "#fca5a5" : "#991b1b";
  const {
    catalogue,
    selections,
    setSelections,
    analysis,
    waiting,
    analyse,
    names,
  } = useAnalysis(apiOrigin(), randomUUID);

  return (
    <ScrollView
      style={{ backgroundColor: dark ? "#111827" : "#fff" }}
      contentContainerStyle={styles.content}
    >
      <Text
        accessibilityRole="header"
        style={[styles.title, { color: foreground }]}
      >
        Signal Lab
      </Text>
      {catalogue.status === "loading" && (
        <Text accessibilityRole="alert" style={{ color: foreground }}>
          Loading recordings
        </Text>
      )}
      {catalogue.status === "failed" && (
        <Text accessibilityRole="alert" style={{ color: errorColor }}>
          {catalogue.code}: {catalogue.message}
        </Text>
      )}
      {catalogue.status === "ready" && (
        <>
          <Text
            accessibilityRole="header"
            style={[styles.heading, { color: foreground }]}
          >
            Registered recordings
          </Text>
          {catalogue.recordings.length === 0 && (
            <Text style={{ color: foreground }}>
              No recordings are registered.
            </Text>
          )}
          {catalogue.recordings.map((recording) => {
            const id = recording.recordingId;
            const chosen = selections.find(
              (selection) => selection.recordingId === id,
            )?.channels;
            const setChannels = (channels: readonly number[]) =>
              setSelections((current) => selectChannels(current, id, channels));
            return (
              <View key={id} style={styles.recording}>
                <Check
                  label={`Analyse ${recording.name}`}
                  text={recording.name}
                  checked={chosen !== undefined}
                  foreground={foreground}
                  onPress={() =>
                    setChannels(chosen ? [] : range(recording.channelCount))
                  }
                />
                <View style={styles.channels}>
                  {range(recording.channelCount).map((channel) => (
                    <Check
                      key={channel}
                      label={`Channel ${channel} of ${recording.name}`}
                      text={String(channel)}
                      foreground={foreground}
                      disabled={chosen === undefined}
                      checked={chosen?.includes(channel) ?? false}
                      onPress={() => {
                        if (!chosen) return;
                        setChannels(
                          chosen.includes(channel)
                            ? chosen.filter((value) => value !== channel)
                            : [...chosen, channel],
                        );
                      }}
                    />
                  ))}
                </View>
                <Text style={{ color: foreground }}>
                  {recording.sampleRateHz / 1000} kHz · {recording.frameCount}{" "}
                  frames · {kibibytes(recording.byteLength)}
                </Text>
              </View>
            );
          })}
          <Pressable
            accessibilityRole="button"
            disabled={selections.length === 0 || waiting}
            onPress={analyse}
            style={[
              styles.check,
              styles.analyse,
              (selections.length === 0 || waiting) && styles.disabled,
            ]}
          >
            <Text style={{ color: foreground }}>Analyse selected</Text>
          </Pressable>
        </>
      )}
      {waiting && (
        <Text accessibilityRole="alert" style={{ color: foreground }}>
          Analysing, please wait
        </Text>
      )}
      {analysis.status === "failed" && (
        <Text accessibilityRole="alert" style={{ color: errorColor }}>
          {analysis.code}: {analysis.message}
        </Text>
      )}
      {analysis.status === "batch" && (
        <View style={styles.section}>
          <Text
            accessibilityRole="header"
            style={[styles.heading, { color: foreground }]}
          >
            Analysis {analysis.batch.state}
          </Text>
          <Text style={{ color: foreground }}>
            Batch {analysis.batch.batchId}
          </Text>
          {analysis.batch.state === "Interrupted" && (
            <Text accessibilityRole="alert" style={{ color: errorColor }}>
              {analysis.batch.error.code}: {analysis.batch.error.message}
            </Text>
          )}
          {analysis.batch.items.map((item) => (
            <ItemView
              key={item.itemId}
              item={item}
              name={names.get(item.recordingId) ?? item.recordingId}
              foreground={foreground}
              errorColor={errorColor}
            />
          ))}
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: {
    width: "100%",
    maxWidth: 896,
    alignSelf: "center",
    padding: 24,
    paddingTop: 48,
    gap: 12,
  },
  title: { fontSize: 32, fontWeight: "700" },
  heading: { fontSize: 18, fontWeight: "600" },
  section: { gap: 8, paddingVertical: 8 },
  recording: {
    borderBottomWidth: 1,
    borderBottomColor: "#6b7280",
    paddingVertical: 8,
  },
  channels: { flexDirection: "row", flexWrap: "wrap" },
  check: {
    minHeight: 44,
    minWidth: 44,
    paddingHorizontal: 8,
    justifyContent: "center",
  },
  analyse: {
    borderWidth: 1,
    borderColor: "#6b7280",
    borderRadius: 4,
    alignSelf: "flex-start",
  },
  disabled: { opacity: 0.5 },
});
