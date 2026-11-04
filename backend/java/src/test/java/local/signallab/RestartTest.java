package local.signallab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.signallab.analysis.AnalysisRepository;
import local.signallab.recordings.RecordingsClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RestartTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static JsonNode get(ConfigurableApplicationContext context, String batchId) throws Exception {
    var port = context.getEnvironment().getRequiredProperty("local.server.port");
    var response = TestStack.HTTP.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/analyses/" + batchId)).build(),
      HttpResponse.BodyHandlers.ofString()
    );
    assertEquals(200, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }

  private static UUID orphan(ConfigurableApplicationContext context) throws Exception {
    var recordingId = UUID.fromString(AnalysisApiTest.recordingId("dc-mono"));
    var snapshot = context.getBean(RecordingsClient.class).find(recordingId).orElseThrow();
    var batchId = UUID.randomUUID();
    context
      .getBean(AnalysisRepository.class)
      .createBatch(
        batchId,
        "pcm16-48k-hann4096-v1",
        Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS),
        List.of(new AnalysisRepository.NewItem.Resolved(UUID.randomUUID(), recordingId, List.of(0), snapshot))
      );
    return batchId;
  }

  @Test
  void completedResultsSurviveARestart() throws Exception {
    JsonNode before;
    String batchId;
    try (var first = TestStack.startApplication(TestStack.baseUrl(TestStack.RECORDINGS))) {
      var response = BatchQueueTest.post(first, AnalysisApiTest.request(item()));
      batchId = JSON.readTree(response.body()).get("batchId").asString();
      before = AnalysisApiTest.awaitTerminal(first, batchId);
    }
    assertEquals("Completed", before.get("state").asString());

    try (var second = TestStack.startApplication(TestStack.baseUrl(TestStack.RECORDINGS))) {
      assertEquals(before, get(second, batchId));
    }
  }

  private static tools.jackson.databind.node.ObjectNode item() throws Exception {
    return AnalysisApiTest.item(AnalysisApiTest.recordingId("dc-mono"), 0);
  }

  @Test
  void aQueuedBatchWithoutMessageIsPublishedAgainAtStartup() throws Exception {
    UUID batchId;
    try (var crashed = TestStack.startApplication(TestStack.baseUrl(TestStack.RECORDINGS))) {
      batchId = orphan(crashed);
    }

    try (
      var restarted = TestStack.startApplication(
        TestStack.baseUrl(TestStack.RECORDINGS),
        "--analysis.queue.reconcile-after=1m"
      )
    ) {
      var batch = AnalysisApiTest.awaitTerminal(restarted, batchId.toString());

      assertEquals("Completed", batch.get("state").asString());
      assertEquals("Succeeded", batch.get("items").get(0).get("state").asString());
    }
  }

  @Test
  void aFreshQueuedBatchIsNotPublishedAgain() throws Exception {
    try (
      var application = TestStack.startApplication(
        TestStack.baseUrl(TestStack.RECORDINGS),
        "--spring.rabbitmq.listener.simple.auto-startup=false"
      )
    ) {
      var batchId = orphan(application);

      try (
        var restarted = TestStack.startApplication(
          TestStack.baseUrl(TestStack.RECORDINGS),
          "--spring.rabbitmq.listener.simple.auto-startup=false",
          "--analysis.queue.reconcile-after=1h"
        )
      ) {
        assertEquals("Queued", get(restarted, batchId.toString()).get("state").asString());
      }
    }
  }
}
