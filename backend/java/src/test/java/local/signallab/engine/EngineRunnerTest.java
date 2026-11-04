package local.signallab.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import local.signallab.contract.Contracts;
import local.signallab.contract.Json;
import local.signallab.contract.Wire;
import local.signallab.recordings.Recording;
import local.signallab.web.InfrastructureFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

class EngineRunnerTest {

  private static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
  private static final Path ENGINE = ROOT.resolve("backend/rust/target/debug/signal-lab-engine");
  private static final JsonMapper JSON = Json.mapper();
  private static final Contracts CONTRACTS = new Contracts(JSON);

  private static Recording recording(String name) throws IOException {
    var catalogue = JSON.readTree(Files.readString(ROOT.resolve(".fixtures/catalog.json")));
    return catalogue
      .get("recordings")
      .valueStream()
      .filter(node -> node.get("name").asString().equals(name))
      .map(node -> JSON.treeToValue(node, Recording.class))
      .findFirst()
      .orElseThrow();
  }

  private static byte[] bytes(Recording recording) throws IOException {
    return Files.readAllBytes(ROOT.resolve(".fixtures/" + recording.recordingId() + ".wav"));
  }

  private static Wire.EngineInput control(Recording recording, int workers) {
    return new Wire.EngineInput(
      Wire.CONTRACT_VERSION,
      Wire.PROFILE_ID,
      UUID.randomUUID(),
      recording,
      List.of(0),
      workers
    );
  }

  private static EngineRunner runner(Path engine, Duration deadline) {
    return new EngineRunner(new EngineProperties(engine, deadline, 1), CONTRACTS, JSON);
  }

  @Test
  void succeedsOnARealRecording() throws Exception {
    var dc = recording("dc-mono");

    var outcome = runner(ENGINE, Duration.ofSeconds(10)).run(control(dc, 1), new ByteArrayInputStream(bytes(dc)));

    var succeeded = assertInstanceOf(EngineOutcome.Succeeded.class, outcome);
    var channel = succeeded.item().result().channels().get(0);
    assertEquals(0.25, channel.rms(), 1e-12);
    assertEquals(0, assertInstanceOf(Wire.Located.class, channel.peak()).bin());
  }

  @Test
  void reportsATypedRefusalForARecordingThatDiffersFromItsSnapshot() throws Exception {
    var dc = recording("dc-mono");
    var changed = bytes(dc);
    changed[changed.length - 1] ^= (byte) 0xFF;

    var outcome = runner(ENGINE, Duration.ofSeconds(10)).run(control(dc, 1), new ByteArrayInputStream(changed));

    var failed = assertInstanceOf(EngineOutcome.Failed.class, outcome);
    assertEquals(Wire.ItemErrorCode.digest_mismatch, failed.item().error().code());
  }

  @Test
  void anEngineThatCannotStartIsAnInfrastructureFailure() throws Exception {
    var dc = recording("dc-mono");
    var missing = ROOT.resolve("backend/rust/target/debug/no-such-engine");

    var failure = assertThrows(InfrastructureFailure.class, () ->
      runner(missing, Duration.ofSeconds(10)).run(control(dc, 1), new ByteArrayInputStream(bytes(dc)))
    );

    assertEquals(Wire.InfrastructureCode.engine_unavailable, failure.code);
  }

  @Test
  @Timeout(30)
  void anEngineKilledFromOutsideIsAnInfrastructureFailure() throws Exception {
    var dc = recording("dc-mono");
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try (var writer = new PipedOutputStream(); var never = new PipedInputStream(writer)) {
      var run = executor.submit(() ->
        assertThrows(InfrastructureFailure.class, () ->
          runner(ENGINE, Duration.ofSeconds(25)).run(control(dc, 1), never)
        )
      );
      var engine = org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
          () ->
            ProcessHandle.current()
              .children()
              .filter(child ->
                child
                  .info()
                  .command()
                  .map(command -> command.endsWith("signal-lab-engine"))
                  .orElse(false)
              )
              .findFirst(),
          java.util.Optional::isPresent
        )
        .orElseThrow();

      engine.destroyForcibly();

      var failure = run.get(20, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(Wire.InfrastructureCode.engine_protocol_error, failure.code);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @Timeout(30)
  void anEngineThatNeverReceivesTheEndOfItsInputIsStoppedAtTheDeadline() throws Exception {
    var dc = recording("dc-mono");
    try (var writer = new PipedOutputStream(); var never = new PipedInputStream(writer)) {
      var failure = assertThrows(InfrastructureFailure.class, () ->
        runner(ENGINE, Duration.ofSeconds(2)).run(control(dc, 1), never)
      );

      assertEquals(Wire.InfrastructureCode.deadline_exceeded, failure.code);
    }
  }
}
