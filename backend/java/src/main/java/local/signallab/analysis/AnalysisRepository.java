package local.signallab.analysis;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import local.signallab.contract.Wire;
import local.signallab.recordings.Recording;
import local.signallab.utils.Sql;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public record AnalysisRepository(Jdbi jdbi) {
  public sealed interface NewItem {
    UUID itemId();

    UUID recordingId();

    List<Integer> channels();

    record Resolved(UUID itemId, UUID recordingId, List<Integer> channels, Recording snapshot) implements NewItem {}

    record Unknown(UUID itemId, UUID recordingId, List<Integer> channels, Wire.ItemError failure) implements NewItem {}
  }

  public enum Finished {
    COMPLETED("Completed"),
    COMPLETED_WITH_ERRORS("CompletedWithErrors"),
    ALL_ITEMS_FAILED("AllItemsFailed");

    public final String sql;

    Finished(String sql) {
      this.sql = sql;
    }
  }

  public record PendingRow(UUID itemId, UUID recordingId, List<Integer> channels, Recording recordingSnapshot) {}

  public record BatchRow(
    String profileId,
    String state,
    OffsetDateTime createdAt,
    @Nullable OffsetDateTime finishedAt,
    Wire.@Nullable InfrastructureError infrastructureError
  ) {}

  public record ItemRow(
    UUID itemId,
    UUID recordingId,
    String state,
    @Nullable Recording recordingSnapshot,
    Wire.@Nullable AnalysisResult result,
    Wire.@Nullable ItemError itemError,
    Wire.@Nullable InfrastructureError infrastructureError,
    @Nullable String engineBuild,
    @Nullable Integer workers
  ) {}

  private static final String INSERT_RESOLVED =
    "INSERT INTO analysis.item (item_id, batch_id, position, recording_id, channels, recording_snapshot, state)" +
    " VALUES (:itemId, :batchId, :position, :recordingId," +
    " ARRAY(SELECT jsonb_array_elements_text(:channels)::smallint), :snapshot, 'Pending')";

  private static final String INSERT_UNKNOWN =
    "INSERT INTO analysis.item (item_id, batch_id, position, recording_id, channels, error, state)" +
    " VALUES (:itemId, :batchId, :position, :recordingId," +
    " ARRAY(SELECT jsonb_array_elements_text(:channels)::smallint), :failure, 'Failed')";

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  public void createBatch(UUID batchId, String profileId, Instant createdAt, List<NewItem> items) {
    jdbi.useTransaction(handle -> {
      Sql.execute(
        handle,
        "INSERT INTO analysis.batch (batch_id, profile_id, state, created_at)" +
          " VALUES (:batchId, :profileId, 'Queued', :createdAt)",
        Sql.params("batchId", batchId, "profileId", profileId, "createdAt", utc(createdAt))
      );
      IntStream.range(0, items.size()).forEach(position ->
        Sql.execute(
          handle,
          items.get(position) instanceof NewItem.Resolved ? INSERT_RESOLVED : INSERT_UNKNOWN,
          items.get(position),
          Sql.params("batchId", batchId, "position", position)
        )
      );
    });
  }

  public boolean claim(UUID batchId, String consumer) {
    return (
      jdbi.withHandle(handle ->
        Sql.execute(
          handle,
          "UPDATE analysis.batch SET state = 'Running', claimed_by = :consumer" +
            " WHERE batch_id = :batchId AND state = 'Queued'",
          Sql.params("batchId", batchId, "consumer", consumer)
        )
      ) == 1
    );
  }

  public List<NewItem.Resolved> pendingItems(UUID batchId) {
    return jdbi
      .withHandle(handle ->
        Sql.query(
          handle,
          "SELECT item_id, recording_id, to_jsonb(channels) AS channels, recording_snapshot" +
            " FROM analysis.item WHERE batch_id = :batchId AND state = 'Pending' ORDER BY position",
          PendingRow.class,
          Sql.params("batchId", batchId)
        )
      )
      .stream()
      .map(row -> new NewItem.Resolved(row.itemId(), row.recordingId(), row.channels(), row.recordingSnapshot()))
      .toList();
  }

  public List<UUID> queuedBefore(Instant cutoff) {
    return jdbi.withHandle(handle ->
      Sql.queryScalarList(
        handle,
        "SELECT batch_id FROM analysis.batch WHERE state = 'Queued' AND created_at < :cutoff ORDER BY created_at",
        UUID.class,
        Sql.params("cutoff", utc(cutoff))
      )
    );
  }

  public Optional<String> state(UUID batchId) {
    return jdbi.withHandle(handle ->
      Sql.queryScalar(
        handle,
        "SELECT state FROM analysis.batch WHERE batch_id = :batchId",
        String.class,
        Sql.params("batchId", batchId)
      )
    );
  }

  public void interruptBatch(UUID batchId, Wire.InfrastructureError error, Instant at) {
    jdbi.useTransaction(handle -> {
      var parameters = Sql.params("batchId", batchId, "error", error, "at", utc(at));
      Sql.execute(
        handle,
        "UPDATE analysis.item SET state = 'Interrupted', error = :error" +
          " WHERE batch_id = :batchId AND state = 'Running'",
        parameters
      );
      Sql.execute(
        handle,
        "UPDATE analysis.item SET state = 'NotRun' WHERE batch_id = :batchId AND state = 'Pending'",
        parameters
      );
      var updated = Sql.execute(
        handle,
        "UPDATE analysis.batch SET state = 'Interrupted', finished_at = :at, error = :error" +
          " WHERE batch_id = :batchId AND state IN ('Queued', 'Running')",
        parameters
      );
      if (updated != 1) {
        throw new IllegalStateException("Batch " + batchId + " is not Queued or Running");
      }
    });
  }

  public void delete(UUID batchId) {
    jdbi.useTransaction(handle -> {
      var parameters = Sql.params("batchId", batchId);
      Sql.execute(handle, "DELETE FROM analysis.item WHERE batch_id = :batchId", parameters);
      Sql.execute(handle, "DELETE FROM analysis.batch WHERE batch_id = :batchId AND state = 'Queued'", parameters);
    });
  }

  public void markRunning(UUID itemId) {
    jdbi.useHandle(handle ->
      Sql.execute(
        handle,
        "UPDATE analysis.item SET state = 'Running' WHERE item_id = :itemId AND state = 'Pending'",
        Sql.params("itemId", itemId)
      )
    );
  }

  public void succeed(UUID itemId, Wire.AnalysisResult result, String engineBuild, int workers) {
    jdbi.useHandle(handle ->
      Sql.execute(
        handle,
        "UPDATE analysis.item SET state = 'Succeeded', result = :result," +
          " engine_build = :engineBuild, workers = :workers WHERE item_id = :itemId AND state = 'Running'",
        Sql.params("itemId", itemId, "result", result, "engineBuild", engineBuild, "workers", workers)
      )
    );
  }

  public void fail(UUID itemId, Wire.ItemError error) {
    jdbi.useHandle(handle ->
      Sql.execute(
        handle,
        "UPDATE analysis.item SET state = 'Failed', error = :error WHERE item_id = :itemId AND state = 'Running'",
        Sql.params("itemId", itemId, "error", error)
      )
    );
  }

  public void finish(UUID batchId, Finished state, Instant at) {
    jdbi.useHandle(handle ->
      Sql.execute(
        handle,
        "UPDATE analysis.batch SET state = :state, finished_at = :at WHERE batch_id = :batchId AND state = 'Running'",
        Sql.params("batchId", batchId, "state", state.sql, "at", utc(at))
      )
    );
  }

  public Optional<Wire.BatchSnapshot> snapshot(UUID batchId) {
    return jdbi.withHandle(handle ->
      Sql.queryOne(
        handle,
        "SELECT profile_id, state, created_at, finished_at, error AS infrastructure_error" +
          " FROM analysis.batch WHERE batch_id = :batchId",
        BatchRow.class,
        Sql.params("batchId", batchId)
      ).map(row -> batch(batchId, row, items(batchId, row.profileId())))
    );
  }

  private Wire.BatchSnapshot batch(UUID batchId, BatchRow row, List<Wire.ItemSnapshot> items) {
    var version = Wire.CONTRACT_VERSION;
    var profileId = row.profileId();
    var created = row.createdAt().toInstant();
    return switch (row.state()) {
      case "Queued" -> new Wire.QueuedBatch(version, batchId, profileId, created, items);
      case "Running" -> new Wire.RunningBatch(version, batchId, profileId, created, items);
      case "Completed" -> new Wire.CompletedBatch(version, batchId, profileId, created, items, finished(row));
      case "CompletedWithErrors" -> new Wire.CompletedWithErrorsBatch(
        version,
        batchId,
        profileId,
        created,
        items,
        finished(row)
      );
      case "AllItemsFailed" -> new Wire.AllItemsFailedBatch(version, batchId, profileId, created, items, finished(row));
      case "Interrupted" -> new Wire.InterruptedBatch(
        version,
        batchId,
        profileId,
        created,
        items,
        finished(row),
        Objects.requireNonNull(row.infrastructureError(), "An interrupted batch has an error")
      );
      default -> throw new IllegalStateException("Unknown batch state " + row.state());
    };
  }

  private static Instant finished(BatchRow row) {
    return Objects.requireNonNull(row.finishedAt(), "A finished batch has a finish time").toInstant();
  }

  private List<Wire.ItemSnapshot> items(UUID batchId, String profileId) {
    return jdbi
      .withHandle(handle ->
        Sql.query(
          handle,
          "SELECT item_id, recording_id, state, recording_snapshot, result," +
            " CASE WHEN state = 'Failed' THEN error END AS item_error," +
            " CASE WHEN state = 'Interrupted' THEN error END AS infrastructure_error," +
            " engine_build, workers FROM analysis.item WHERE batch_id = :batchId ORDER BY position",
          ItemRow.class,
          Sql.params("batchId", batchId)
        )
      )
      .stream()
      .map(row -> item(profileId, row))
      .toList();
  }

  private static Wire.ItemSnapshot item(String profileId, ItemRow row) {
    var itemId = row.itemId();
    var recordingId = row.recordingId();
    return switch (row.state()) {
      case "Pending" -> new Wire.PendingItem(itemId, recordingId);
      case "Running" -> new Wire.RunningItem(itemId, recordingId);
      case "Succeeded" -> {
        var snapshot = Objects.requireNonNull(row.recordingSnapshot(), "A succeeded item has a snapshot");
        yield new Wire.SucceededItem(
          itemId,
          recordingId,
          snapshot.sha256(),
          profileId,
          Objects.requireNonNull(row.engineBuild(), "A succeeded item has an engine build"),
          Objects.requireNonNull(row.workers(), "A succeeded item has a worker setting"),
          Objects.requireNonNull(row.result(), "A succeeded item has a result"),
          snapshot.provenance()
        );
      }
      case "Failed" -> new Wire.FailedItem(
        itemId,
        recordingId,
        Objects.requireNonNull(row.itemError(), "A failed item has an error")
      );
      case "Interrupted" -> new Wire.InterruptedItem(
        itemId,
        recordingId,
        Objects.requireNonNull(row.infrastructureError(), "An interrupted item has an error")
      );
      case "NotRun" -> new Wire.NotRunItem(itemId, recordingId, Wire.NotRunReason.batch_interrupted);
      default -> throw new IllegalStateException("Unknown item state " + row.state());
    };
  }
}
