package local.signallab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.rabbitmq.client.ConnectionFactory;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import local.signallab.analysis.AnalysisRepository;
import local.signallab.contract.Contracts;
import local.signallab.contract.Json;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class BatchQueueTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Contracts CONTRACTS = new Contracts(Json.mapper());

  private static ObjectNode oneItem() throws Exception {
    return AnalysisApiTest.request(AnalysisApiTest.item(AnalysisApiTest.recordingId("dc-mono"), 0));
  }

  static HttpResponse<String> post(ConfigurableApplicationContext context, ObjectNode body) throws Exception {
    var port = context.getEnvironment().getRequiredProperty("local.server.port");
    return TestStack.HTTP.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/analyses"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  private static long batches() throws Exception {
    try (
      var connection = DriverManager.getConnection(
        TestStack.DATABASE.getJdbcUrl(),
        "analysis",
        TestStack.ANALYSIS_PASSWORD
      );
      var statement = connection.createStatement();
      var rows = statement.executeQuery("SELECT count(*) FROM analysis.batch")
    ) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static com.rabbitmq.client.Connection broker() throws Exception {
    var factory = new ConnectionFactory();
    factory.setHost(TestStack.BROKER.getHost());
    factory.setPort(TestStack.BROKER.getAmqpPort());
    factory.setUsername(TestStack.BROKER_USER);
    factory.setPassword(TestStack.BROKER_PASSWORD);
    return factory.newConnection();
  }

  @Test
  void aStoppedBrokerInterruptsTheBatchAndAnswers503() throws Exception {
    try (var stopping = TestStack.broker()) {
      stopping.start();
      try (
        var application = TestStack.startApplication(
          TestStack.baseUrl(TestStack.RECORDINGS),
          "--spring.rabbitmq.host=" + stopping.getHost(),
          "--spring.rabbitmq.port=" + stopping.getAmqpPort()
        )
      ) {
        stopping.stop();

        var response = post(application, oneItem());

        assertEquals(503, response.statusCode(), response.body());
        var batch = JSON.readTree(response.body());
        CONTRACTS.validate("BatchSnapshot", batch);
        assertEquals("Interrupted", batch.get("state").asString());
        assertEquals("broker_unavailable", batch.get("error").get("code").asString());
        assertEquals("NotRun", batch.get("items").get(0).get("state").asString());
      }
    }
  }

  @Test
  void aBrokerThatDoesNotAnswerAtAllIsReportedWithinTheDeadline() throws Exception {
    try (
      var application = TestStack.startApplication(
        TestStack.baseUrl(TestStack.RECORDINGS),
        "--spring.rabbitmq.host=192.0.2.1",
        "--spring.rabbitmq.listener.simple.auto-startup=false"
      )
    ) {
      var response = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
        post(application, oneItem())
      );

      assertEquals(503, response.statusCode(), response.body());
      var batch = JSON.readTree(response.body());
      assertEquals("broker_unavailable", batch.get("error").get("code").asString());
    }
  }

  @Test
  void aFullQueueAnswersCapacityBusyAndCreatesNoBatch() throws Exception {
    try (
      var application = TestStack.startApplication(
        TestStack.baseUrl(TestStack.RECORDINGS),
        "--spring.rabbitmq.listener.simple.auto-startup=false",
        "--analysis.queue.max-depth=2"
      )
    ) {
      var before = batches();
      assertEquals(202, post(application, oneItem()).statusCode());
      assertEquals(202, post(application, oneItem()).statusCode());

      var refused = post(application, oneItem());

      assertEquals(429, refused.statusCode(), refused.body());
      assertEquals("capacity_busy", JSON.readTree(refused.body()).get("code").asString());
      assertEquals(before + 2, batches());
    }
  }

  @Test
  void aBatchWhoseConsumerDiedBeforeTheAckIsInterruptedAndNotRunAgain() throws Exception {
    var queue = "analysis.batches.redelivery-" + UUID.randomUUID();
    try (
      var producer = TestStack.startApplication(
        TestStack.baseUrl(TestStack.RECORDINGS),
        "--analysis.queue.name=" + queue,
        "--spring.rabbitmq.listener.simple.auto-startup=false"
      )
    ) {
      var queued = JSON.readTree(post(producer, oneItem()).body());
      var batchId = queued.get("batchId").asString();
      assertEquals(true, producer.getBean(AnalysisRepository.class).claim(UUID.fromString(batchId), "dead-consumer"));
      try (var connection = broker(); var channel = connection.createChannel()) {
        assertNotNull(channel.basicGet(queue, false));
      }

      try (
        var consumer = TestStack.startApplication(
          TestStack.baseUrl(TestStack.RECORDINGS),
          "--analysis.queue.name=" + queue
        )
      ) {
        var batch = AnalysisApiTest.awaitTerminal(consumer, batchId);

        CONTRACTS.validate("BatchSnapshot", batch);
        assertEquals("Interrupted", batch.get("state").asString());
        assertEquals("application_stopped", batch.get("error").get("code").asString());
        assertEquals("NotRun", batch.get("items").get(0).get("state").asString());
        try (var connection = broker(); var channel = connection.createChannel()) {
          TestStack.await().until(() -> channel.messageCount(queue) == 0);
        }
      }
    }
  }

  @Test
  void aMessageForAFinishedBatchIsDroppedWithoutChangingIt() throws Exception {
    try (var application = TestStack.startApplication(TestStack.baseUrl(TestStack.RECORDINGS))) {
      var queued = JSON.readTree(post(application, oneItem()).body());
      var batchId = queued.get("batchId").asString();
      var finished = AnalysisApiTest.awaitTerminal(application, batchId);
      var queue = application.getEnvironment().getRequiredProperty("analysis.queue.name");

      try (var connection = broker(); var channel = connection.createChannel()) {
        channel.basicPublish("", queue, null, batchId.getBytes(StandardCharsets.UTF_8));
        TestStack.await().until(() -> channel.messageCount(queue) == 0);
      }

      assertEquals(finished, AnalysisApiTest.awaitTerminal(application, batchId));
    }
  }

  @Test
  void anUnparseableMessageGoesToTheDeadLetterQueue() throws Exception {
    try (var application = TestStack.startApplication(TestStack.baseUrl(TestStack.RECORDINGS))) {
      var queue = application.getEnvironment().getRequiredProperty("analysis.queue.name");
      try (var connection = broker(); var channel = connection.createChannel()) {
        channel.basicPublish("", queue, null, "not-a-uuid".getBytes(StandardCharsets.UTF_8));

        var dead = TestStack.await().until(() -> channel.basicGet(queue + ".dead", true), response -> response != null);

        assertEquals("not-a-uuid", new String(dead.getBody(), StandardCharsets.UTF_8));
      }
    }
  }
}
