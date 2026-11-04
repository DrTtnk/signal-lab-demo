package local.signallab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import local.signallab.analysis.AnalysisRepository;
import local.signallab.contract.Contracts;
import local.signallab.contract.Wire;
import local.signallab.recordings.RecordingsClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

class AnalysisQueueTest {

  private static ConfigurableApplicationContext application;
  private static AnalysisRepository repository;
  private static Contracts contracts;

  @BeforeAll
  static void start() {
    application = TestStack.startApplication(TestStack.baseUrl(TestStack.RECORDINGS));
    repository = application.getBean(AnalysisRepository.class);
    contracts = application.getBean(Contracts.class);
  }

  @AfterAll
  static void stop() {
    application.close();
  }

  private static UUID queuedBatch() {
    var batchId = UUID.randomUUID();
    var recording = application.getBean(RecordingsClient.class).list().get(0);
    var item = new AnalysisRepository.NewItem.Resolved(
      UUID.randomUUID(),
      recording.recordingId(),
      List.of(0),
      recording
    );
    repository.createBatch(
      batchId,
      "pcm16-48k-hann4096-v1",
      Instant.now().truncatedTo(ChronoUnit.MICROS),
      List.of(item)
    );
    return batchId;
  }

  @Test
  void aNewBatchIsQueuedAndMatchesTheContract() {
    var snapshot = repository.snapshot(queuedBatch()).orElseThrow();
    assertInstanceOf(Wire.QueuedBatch.class, snapshot);
    contracts.validate("BatchSnapshot", snapshot);
  }

  @Test
  void aClaimedBatchIsRunning() {
    var batchId = queuedBatch();
    assertTrue(repository.claim(batchId, "consumer-a"));
    var snapshot = repository.snapshot(batchId).orElseThrow();
    assertInstanceOf(Wire.RunningBatch.class, snapshot);
    contracts.validate("BatchSnapshot", snapshot);
  }

  @Test
  void aBatchCanBeClaimedOnlyOnce() {
    var batchId = queuedBatch();
    assertTrue(repository.claim(batchId, "consumer-a"));
    assertFalse(repository.claim(batchId, "consumer-b"));
  }

  @Test
  void anUnknownBatchCannotBeClaimed() {
    assertFalse(repository.claim(UUID.randomUUID(), "consumer-a"));
  }

  @Test
  void exactlyOneOfManyConcurrentConsumersWinsTheClaim() throws Exception {
    var batchId = queuedBatch();
    var consumers = 8;
    try (var executor = Executors.newFixedThreadPool(consumers)) {
      var attempts = java.util.stream.IntStream.range(0, consumers)
        .<Callable<Boolean>>mapToObj(number -> () -> repository.claim(batchId, "consumer-" + number))
        .toList();
      var wins = 0;
      for (var future : executor.invokeAll(attempts)) {
        wins += future.get() ? 1 : 0;
      }
      assertEquals(1, wins);
    }
  }
}
