package local.signallab.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import local.signallab.recordings.Recording;
import local.signallab.utils.Jsonb;

public final class Wire {

  private Wire() {}

  public static final String CONTRACT_VERSION = "1";
  public static final String PROFILE_ID = "pcm16-48k-hann4096-v1";
  public static final int SPECTRUM_BINS = 2049;

  private static void require(boolean condition, String what) {
    if (!condition) {
      throw new IllegalArgumentException(what);
    }
  }

  private static void requireChannels(List<Integer> channels) {
    require(channels.size() >= 1 && channels.size() <= 4, "one to four channels");
    require(channels.stream().allMatch(channel -> channel >= 0 && channel <= 3), "channel index 0 to 3");
    require(new HashSet<>(channels).size() == channels.size(), "unique channels");
  }

  private static void requireVersion(String contractVersion, String profileId) {
    require(contractVersion.equals(CONTRACT_VERSION), "contract version " + CONTRACT_VERSION);
    require(profileId.equals(PROFILE_ID), "profile " + PROFILE_ID);
  }

  public enum ItemErrorCode {
    unknown_recording,

    channel_unavailable,

    unsupported_recording,

    truncated_recording,

    digest_mismatch,

    insufficient_frames,
  }

  public enum InfrastructureCode {
    engine_unavailable,

    recordings_unavailable,

    database_unavailable,

    deadline_exceeded,

    engine_protocol_error,

    application_stopped,

    broker_unavailable,
  }

  public enum NotRunReason {
    batch_interrupted,
  }

  public record ItemError(ItemErrorCode code, String message) implements Jsonb {
    public ItemError {
      require(message.length() >= 1 && message.length() <= 1024, "error message of 1 to 1024 characters");
    }
  }

  public record InfrastructureError(InfrastructureCode code, String message) implements Jsonb {
    public InfrastructureError {
      require(message.length() >= 1 && message.length() <= 1024, "error message of 1 to 1024 characters");
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Located.class, name = "Peak"),
    @JsonSubTypes.Type(value = Absent.class, name = "NoPeak"),
  })
  public sealed interface Peak permits Located, Absent {}

  public record Located(int bin, double frequencyHz) implements Peak {
    public Located {
      require(bin >= 0 && bin < SPECTRUM_BINS, "peak bin inside the spectrum");
      require(frequencyHz >= 0 && frequencyHz <= 24000, "peak frequency 0 to 24000 Hz");
    }
  }

  public record Absent() implements Peak {}

  public record ChannelResult(int channelIndex, double rms, List<Double> meanPsd, List<Double> bandPowers, Peak peak) {
    public ChannelResult {
      require(channelIndex >= 0 && channelIndex <= 3, "channel index 0 to 3");
      require(rms >= 0, "non-negative rms");
      require(
        meanPsd.size() == SPECTRUM_BINS && meanPsd.stream().allMatch(value -> value >= 0),
        "spectrum of 2049 bins"
      );
      require(bandPowers.size() == 3 && bandPowers.stream().allMatch(value -> value >= 0), "three band powers");
    }
  }

  public record AnalysisResult(
    long processedFrames,
    int ignoredTailFrames,
    long processedWindows,
    List<ChannelResult> channels
  ) implements Jsonb {
    public AnalysisResult {
      require(processedFrames >= 1 && processedWindows >= 1, "at least one window");
      require(ignoredTailFrames >= 0 && ignoredTailFrames <= 4095, "ignored tail below one window");
      require(channels.size() >= 1 && channels.size() <= 4, "one to four channels");
    }
  }

  public record EngineInput(
    String contractVersion,
    String profileId,
    UUID itemId,
    Recording recording,
    List<Integer> channels,
    int workers
  ) {
    public EngineInput {
      requireVersion(contractVersion, profileId);
      requireChannels(channels);
      require(workers == 1 || workers == 2 || workers == 4, "workers 1, 2 or 4");
    }
  }

  public record RequestItem(UUID itemId, UUID recordingId, List<Integer> channels) {
    public RequestItem {
      requireChannels(channels);
    }
  }

  public record AnalysisRequest(String contractVersion, String profileId, List<RequestItem> items) {
    public AnalysisRequest {
      requireVersion(contractVersion, profileId);
      require(!items.isEmpty(), "at least one item");
      require(items.stream().map(RequestItem::itemId).distinct().count() == items.size(), "unique item identifiers");
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "state")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = PendingItem.class, name = "Pending"),
    @JsonSubTypes.Type(value = RunningItem.class, name = "Running"),
    @JsonSubTypes.Type(value = SucceededItem.class, name = "Succeeded"),
    @JsonSubTypes.Type(value = FailedItem.class, name = "Failed"),
    @JsonSubTypes.Type(value = InterruptedItem.class, name = "Interrupted"),
    @JsonSubTypes.Type(value = NotRunItem.class, name = "NotRun"),
  })
  public sealed interface ItemSnapshot
    permits PendingItem, RunningItem, SucceededItem, FailedItem, InterruptedItem, NotRunItem
  {
    UUID itemId();

    UUID recordingId();
  }

  public record PendingItem(UUID itemId, UUID recordingId) implements ItemSnapshot {}

  public record RunningItem(UUID itemId, UUID recordingId) implements ItemSnapshot {}

  public record SucceededItem(
    UUID itemId,
    UUID recordingId,
    String recordingSha256,
    String profileId,
    String engineBuild,
    int workers,
    AnalysisResult result,
    Recording.Provenance recordingProvenance
  ) implements ItemSnapshot {
    public SucceededItem {
      require(profileId.equals(PROFILE_ID), "profile " + PROFILE_ID);
      require(recordingSha256.matches("[0-9a-f]{64}"), "lowercase SHA-256 digest");
      require(engineBuild.length() >= 1 && engineBuild.length() <= 256, "engine build of 1 to 256 characters");
      require(workers == 1 || workers == 2 || workers == 4, "workers 1, 2 or 4");
    }
  }

  public record FailedItem(UUID itemId, UUID recordingId, ItemError error) implements ItemSnapshot {}

  public record InterruptedItem(UUID itemId, UUID recordingId, InfrastructureError error) implements ItemSnapshot {}

  public record NotRunItem(UUID itemId, UUID recordingId, NotRunReason reason) implements ItemSnapshot {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "state")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = QueuedBatch.class, name = "Queued"),
    @JsonSubTypes.Type(value = RunningBatch.class, name = "Running"),
    @JsonSubTypes.Type(value = CompletedBatch.class, name = "Completed"),
    @JsonSubTypes.Type(value = CompletedWithErrorsBatch.class, name = "CompletedWithErrors"),
    @JsonSubTypes.Type(value = AllItemsFailedBatch.class, name = "AllItemsFailed"),
    @JsonSubTypes.Type(value = InterruptedBatch.class, name = "Interrupted"),
  })
  public sealed interface BatchSnapshot
    permits QueuedBatch, RunningBatch, CompletedBatch, CompletedWithErrorsBatch, AllItemsFailedBatch, InterruptedBatch
  {
    UUID batchId();

    String profileId();

    List<ItemSnapshot> items();
  }

  public record QueuedBatch(
    String contractVersion,
    UUID batchId,
    String profileId,
    Instant createdAt,
    List<ItemSnapshot> items
  ) implements BatchSnapshot {
    public QueuedBatch {
      requireVersion(contractVersion, profileId);
      require(!items.isEmpty(), "at least one item");
    }
  }

  public record RunningBatch(
    String contractVersion,
    UUID batchId,
    String profileId,
    Instant createdAt,
    List<ItemSnapshot> items
  ) implements BatchSnapshot {
    public RunningBatch {
      requireVersion(contractVersion, profileId);
      require(!items.isEmpty(), "at least one item");
    }
  }

  public record CompletedBatch(
    String contractVersion,
    UUID batchId,
    String profileId,
    Instant createdAt,
    List<ItemSnapshot> items,
    Instant finishedAt
  ) implements BatchSnapshot {
    public CompletedBatch {
      requireVersion(contractVersion, profileId);
      require(!items.isEmpty(), "at least one item");
    }
  }

  public record CompletedWithErrorsBatch(
    String contractVersion,
    UUID batchId,
    String profileId,
    Instant createdAt,
    List<ItemSnapshot> items,
    Instant finishedAt
  ) implements BatchSnapshot {
    public CompletedWithErrorsBatch {
      requireVersion(contractVersion, profileId);
      require(!items.isEmpty(), "at least one item");
    }
  }

  public record AllItemsFailedBatch(
    String contractVersion,
    UUID batchId,
    String profileId,
    Instant createdAt,
    List<ItemSnapshot> items,
    Instant finishedAt
  ) implements BatchSnapshot {
    public AllItemsFailedBatch {
      requireVersion(contractVersion, profileId);
      require(!items.isEmpty(), "at least one item");
    }
  }

  public record InterruptedBatch(
    String contractVersion,
    UUID batchId,
    String profileId,
    Instant createdAt,
    List<ItemSnapshot> items,
    Instant finishedAt,
    InfrastructureError error
  ) implements BatchSnapshot {
    public InterruptedBatch {
      requireVersion(contractVersion, profileId);
      require(!items.isEmpty(), "at least one item");
    }
  }
}
