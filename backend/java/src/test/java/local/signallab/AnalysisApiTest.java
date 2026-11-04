package local.signallab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;
import local.signallab.contract.Contracts;
import local.signallab.contract.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AnalysisApiTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Contracts CONTRACTS = new Contracts(Json.mapper());
  private static ConfigurableApplicationContext application;

  @BeforeAll
  static void start() {
    application = TestStack.startApplication(TestStack.baseUrl(TestStack.RECORDINGS));
  }

  @AfterAll
  static void stop() {
    application.close();
  }

  static String recordingId(String name) throws Exception {
    var catalogue = JSON.readTree(Files.readString(TestStack.ROOT.resolve(".fixtures/catalog.json")));
    return catalogue
      .get("recordings")
      .valueStream()
      .filter(node -> node.get("name").asString().equals(name))
      .findFirst()
      .orElseThrow()
      .get("recordingId")
      .asString();
  }

  static ObjectNode item(String recordingId, int... channels) {
    var node = JSON.createObjectNode();
    node.put("itemId", UUID.randomUUID().toString());
    node.put("recordingId", recordingId);
    var array = node.putArray("channels");
    for (var channel : channels) {
      array.add(channel);
    }
    return node;
  }

  static ObjectNode request(ObjectNode... items) {
    var node = JSON.createObjectNode();
    node.put("contractVersion", "1");
    node.put("profileId", "pcm16-48k-hann4096-v1");
    var array = node.putArray("items");
    for (var item : items) {
      array.add(item);
    }
    return node;
  }

  private static HttpResponse<String> send(String method, String path, String body) throws Exception {
    var port = application.getEnvironment().getRequiredProperty("local.server.port");
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    if (body.isEmpty()) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return TestStack.HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode submit(ObjectNode request, int expectedStatus) throws Exception {
    var response = send("POST", "/analyses", JSON.writeValueAsString(request));
    assertEquals(expectedStatus, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }

  private static final Set<String> TERMINAL = Set.of(
    "Completed",
    "CompletedWithErrors",
    "AllItemsFailed",
    "Interrupted"
  );

  private static JsonNode analyse(ObjectNode request) throws Exception {
    var queued = submit(request, 202);
    CONTRACTS.validate("BatchSnapshot", queued);
    assertEquals("Queued", queued.get("state").asString());
    return awaitTerminal(application, queued.get("batchId").asString());
  }

  static JsonNode awaitTerminal(ConfigurableApplicationContext context, String batchId) {
    var port = context.getEnvironment().getRequiredProperty("local.server.port");
    return TestStack.await().until(
      () -> {
        var response = TestStack.HTTP.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/analyses/" + batchId)).build(),
          HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
      },
      batch -> TERMINAL.contains(batch.get("state").asString())
    );
  }

  private static long count(String table) throws Exception {
    try (
      var connection = DriverManager.getConnection(
        TestStack.DATABASE.getJdbcUrl(),
        "analysis",
        TestStack.ANALYSIS_PASSWORD
      );
      var statement = connection.createStatement();
      var rows = statement.executeQuery("SELECT count(*) FROM analysis." + table)
    ) {
      rows.next();
      return rows.getLong(1);
    }
  }

  @Test
  void analysesARegisteredRecordingAndPersistsTheResult() throws Exception {
    var batch = analyse(request(item(recordingId("dc-mono"), 0)));

    CONTRACTS.validate("BatchSnapshot", batch);
    assertEquals("Completed", batch.get("state").asString());
    var item = batch.get("items").get(0);
    assertEquals("Succeeded", item.get("state").asString());
    assertEquals(0.25, item.get("result").get("channels").get(0).get("rms").asDouble(), 1e-12);

    var persisted = send("GET", "/analyses/" + batch.get("batchId").asString(), "");
    assertEquals(200, persisted.statusCode());
    assertEquals(batch, JSON.readTree(persisted.body()));
  }

  @Test
  void analysesTheSelectedChannelsOfAStereoRecordingInRequestedOrder() throws Exception {
    var batch = analyse(request(item(recordingId("stereo-with-tail"), 1, 0)));

    var result = batch.get("items").get(0).get("result");
    assertEquals(1, result.get("channels").get(0).get("channelIndex").asInt());
    assertEquals(0, result.get("channels").get(1).get("channelIndex").asInt());
    assertEquals(3, result.get("ignoredTailFrames").asInt());
  }

  @Test
  void theWorkerSettingReachesTheEngineAndDoesNotChangeTheResult() throws Exception {
    var single = analyse(request(item(recordingId("stereo-with-tail"), 0, 1)));
    try (var parallel = TestStack.startApplication(TestStack.baseUrl(TestStack.RECORDINGS), "--engine.workers=4")) {
      var response = BatchQueueTest.post(parallel, request(item(recordingId("stereo-with-tail"), 0, 1)));
      assertEquals(202, response.statusCode(), response.body());
      var batch = awaitTerminal(parallel, JSON.readTree(response.body()).get("batchId").asString());

      assertEquals("Completed", batch.get("state").asString());
      assertEquals(4, batch.get("items").get(0).get("workers").asInt());
      assertEquals(1, single.get("items").get(0).get("workers").asInt());
      assertEquals(single.get("items").get(0).get("result"), batch.get("items").get(0).get("result"));
    }
  }

  @Test
  void anUnknownRecordingIsAnItemFailureAndOtherItemsStillRun() throws Exception {
    var batch = analyse(request(item(UUID.randomUUID().toString(), 0), item(recordingId("zero-mono"), 0)));

    CONTRACTS.validate("BatchSnapshot", batch);
    assertEquals("CompletedWithErrors", batch.get("state").asString());
    assertEquals("Failed", batch.get("items").get(0).get("state").asString());
    assertEquals("unknown_recording", batch.get("items").get(0).get("error").get("code").asString());
    assertEquals("Succeeded", batch.get("items").get(1).get("state").asString());
  }

  @Test
  void anUnavailableChannelIsAnItemFailure() throws Exception {
    var batch = analyse(request(item(recordingId("dc-mono"), 3)));

    assertEquals("AllItemsFailed", batch.get("state").asString());
    assertEquals("channel_unavailable", batch.get("items").get(0).get("error").get("code").asString());
  }

  @Test
  void aMalformedEnvelopeCreatesNoBatchAndNamesTheField() throws Exception {
    var before = count("batch");
    var malformed = request(item(recordingId("dc-mono"), 0));
    malformed.put("extra", 1);

    var response = send("POST", "/analyses", JSON.writeValueAsString(malformed));

    assertEquals(400, response.statusCode());
    assertEquals(true, response.body().contains("extra"), response.body());
    assertEquals(before, count("batch"));
  }

  @Test
  void duplicateItemIdentifiersCreateNoBatch() throws Exception {
    var before = count("batch");
    var first = item(recordingId("dc-mono"), 0);
    var second = item(recordingId("zero-mono"), 0);
    second.put("itemId", first.get("itemId").asString());

    var response = send("POST", "/analyses", JSON.writeValueAsString(request(first, second)));

    assertEquals(400, response.statusCode());
    assertEquals(before, count("batch"));
  }

  @Test
  void anUnavailableEngineInterruptsTheBatchAndIsPersisted() throws Exception {
    try (
      var broken = TestStack.startApplication(
        TestStack.baseUrl(TestStack.RECORDINGS),
        "--engine.path=" + TestStack.ROOT.resolve("backend/rust/target/debug/no-such-engine")
      )
    ) {
      var port = broken.getEnvironment().getRequiredProperty("local.server.port");
      var body = JSON.writeValueAsString(request(item(recordingId("dc-mono"), 0)));
      var response = TestStack.HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/analyses"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      );

      assertEquals(202, response.statusCode(), response.body());
      var batch = awaitTerminal(broken, JSON.readTree(response.body()).get("batchId").asString());
      CONTRACTS.validate("BatchSnapshot", batch);
      assertEquals("Interrupted", batch.get("state").asString());
      assertEquals("engine_unavailable", batch.get("error").get("code").asString());
      assertEquals("Interrupted", batch.get("items").get(0).get("state").asString());
    }
  }

  @Test
  void anUnknownBatchIsNotFound() throws Exception {
    assertEquals(404, send("GET", "/analyses/" + UUID.randomUUID(), "").statusCode());
  }
}
