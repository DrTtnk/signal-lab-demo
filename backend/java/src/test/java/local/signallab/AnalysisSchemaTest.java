package local.signallab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.context.ConfigurableApplicationContext;

class AnalysisSchemaTest {

  private static final Optional<String> NONE = Optional.empty();
  private static ConfigurableApplicationContext application;

  @BeforeAll
  static void migrate() {
    application = TestStack.startApplication(TestStack.baseUrl(TestStack.RECORDINGS));
  }

  @AfterAll
  static void stop() {
    application.close();
  }

  private static void refused(String sqlState, Executable action) {
    assertEquals(sqlState, assertThrows(SQLException.class, action).getSQLState());
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(TestStack.DATABASE.getJdbcUrl(), "analysis", TestStack.ANALYSIS_PASSWORD);
  }

  private static UUID insertBatch(Connection connection, String state, String finishedAt) throws SQLException {
    return insertBatch(connection, state, finishedAt, state.equals("Queued") || state.equals("Interrupted"));
  }

  private static UUID insertBatch(Connection connection, String state, String finishedAt, boolean unclaimed)
    throws SQLException {
    var id = UUID.randomUUID();
    try (
      var statement = connection.prepareStatement(
        "INSERT INTO analysis.batch (batch_id, profile_id, state, created_at, finished_at," +
          " claimed_by, error) VALUES (?, 'pcm16-48k-hann4096-v1', ?, now(), " +
          finishedAt +
          ", ?, ?::jsonb)"
      )
    ) {
      statement.setObject(1, id);
      statement.setString(2, state);
      statement.setString(3, unclaimed ? null : "test-consumer");
      statement.setString(4, state.equals("Interrupted") ? "{\"code\":\"engine_unavailable\"}" : null);
      statement.executeUpdate();
    }
    return id;
  }

  private static void insertItem(
    Connection connection,
    UUID batch,
    int position,
    String state,
    Optional<String> result,
    Optional<String> error
  ) throws SQLException {
    try (
      var statement = connection.prepareStatement(
        "INSERT INTO analysis.item (item_id, batch_id, position, recording_id, channels," +
          " recording_snapshot, state, result, error, engine_build, workers)" +
          " VALUES (?, ?, ?, ?, '{0}', '{}'::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?)"
      )
    ) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, batch);
      statement.setInt(3, position);
      statement.setObject(4, UUID.randomUUID());
      statement.setString(5, state);
      statement.setString(6, result.orElse(null));
      statement.setString(7, error.orElse(null));
      statement.setString(8, result.map(r -> "engine-test").orElse(null));
      statement.setObject(9, result.map(r -> (Object) 1).orElse(null), java.sql.Types.SMALLINT);
      statement.executeUpdate();
    }
  }

  @Test
  void aSucceededItemNeedsItsResult() throws Exception {
    try (var connection = connect()) {
      var batch = insertBatch(connection, "Running", "NULL");
      refused("23514", () -> insertItem(connection, batch, 0, "Succeeded", NONE, NONE));
      insertItem(connection, batch, 0, "Succeeded", Optional.of("{\"ok\":true}"), NONE);
    }
  }

  @Test
  void aFailedItemNeedsItsErrorAndNoResult() throws Exception {
    try (var connection = connect()) {
      var batch = insertBatch(connection, "Running", "NULL");
      refused("23514", () -> insertItem(connection, batch, 0, "Failed", NONE, NONE));
      refused("23514", () ->
        insertItem(connection, batch, 1, "Failed", Optional.of("{\"ok\":true}"), Optional.of("{\"code\":\"x\"}"))
      );
      insertItem(connection, batch, 2, "Failed", NONE, Optional.of("{\"code\":\"unknown_recording\"}"));
    }
  }

  @Test
  void aPendingItemCarriesNoOutcome() throws Exception {
    try (var connection = connect()) {
      var batch = insertBatch(connection, "Running", "NULL");
      refused("23514", () -> insertItem(connection, batch, 0, "Pending", Optional.of("{\"ok\":true}"), NONE));
      insertItem(connection, batch, 1, "Pending", NONE, NONE);
    }
  }

  @Test
  void aSnapshotMayBeAbsentOnlyForAnUnknownRecording() throws Exception {
    try (var connection = connect()) {
      var batch = insertBatch(connection, "Running", "NULL");
      refused("23514", () ->
        connection
          .createStatement()
          .executeUpdate(
            "INSERT INTO analysis.item (item_id, batch_id, position, recording_id," +
              " channels, state) VALUES (gen_random_uuid(), '" +
              batch +
              "', 0, gen_random_uuid(), '{0}', 'Pending')"
          )
      );
      connection
        .createStatement()
        .executeUpdate(
          "INSERT INTO analysis.item (item_id, batch_id, position, recording_id, channels," +
            " state, error) VALUES (gen_random_uuid(), '" +
            batch +
            "', 1, gen_random_uuid(), '{0}', 'Failed'," +
            " '{\"code\":\"unknown_recording\"}'::jsonb)"
        );
    }
  }

  @Test
  void anUnknownItemStateIsRefused() throws Exception {
    try (var connection = connect()) {
      var batch = insertBatch(connection, "Running", "NULL");
      refused("23514", () -> insertItem(connection, batch, 0, "Almost", NONE, NONE));
    }
  }

  @Test
  void aPositionIsUsedOncePerBatch() throws Exception {
    try (var connection = connect()) {
      var batch = insertBatch(connection, "Running", "NULL");
      insertItem(connection, batch, 0, "Pending", NONE, NONE);
      refused("23505", () -> insertItem(connection, batch, 0, "Pending", NONE, NONE));
    }
  }

  @Test
  void anItemNeedsItsBatch() throws Exception {
    try (var connection = connect()) {
      refused("23503", () -> insertItem(connection, UUID.randomUUID(), 0, "Pending", NONE, NONE));
    }
  }

  @Test
  void aBatchIsFinishedExactlyWhenItIsNotRunning() throws Exception {
    try (var connection = connect()) {
      refused("23514", () -> insertBatch(connection, "Running", "now()"));
      refused("23514", () -> insertBatch(connection, "Completed", "NULL"));
      insertBatch(connection, "Completed", "now()");
      insertBatch(connection, "Running", "NULL");
    }
  }

  @Test
  void theAnalysisRoleCannotUseTheRecordingsSchema() throws Exception {
    try (var connection = connect(); var statement = connection.createStatement()) {
      var error = assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM recordings.recording"));
      assertEquals("42501", error.getSQLState());
    }
  }

  @Test
  void aQueuedBatchHasNoFinishTimeAndNoClaim() throws Exception {
    try (var connection = connect()) {
      refused("23514", () -> insertBatch(connection, "Queued", "now()"));
      refused("23514", () -> insertBatch(connection, "Queued", "NULL", false));
      insertBatch(connection, "Queued", "NULL");
    }
  }

  @Test
  void aRunningOrFinishedBatchNeedsItsClaim() throws Exception {
    try (var connection = connect()) {
      refused("23514", () -> insertBatch(connection, "Running", "NULL", true));
      refused("23514", () -> insertBatch(connection, "Completed", "now()", true));
      insertBatch(connection, "Running", "NULL", false);
    }
  }
}
