package local.signallab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RecordingsCatalogueTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static HttpResponse<String> get(ConfigurableApplicationContext context, String path)
    throws IOException, InterruptedException {
    var port = context.getEnvironment().getRequiredProperty("local.server.port");
    return TestStack.HTTP.send(
      HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  private static Set<String> names(JsonNode recordings) {
    return recordings
      .valueStream()
      .map(node -> node.get("name").asString())
      .collect(Collectors.toSet());
  }

  @Test
  void listsTheRecordingsRegisteredByTheRecordingsService() throws Exception {
    var recordingsUrl = TestStack.baseUrl(TestStack.RECORDINGS);
    try (var context = TestStack.startApplication(recordingsUrl)) {
      var response = get(context, "/recordings");

      assertEquals(200, response.statusCode());
      var listed = JSON.readTree(response.body());
      var direct = JSON.readTree(
        TestStack.HTTP.send(
          HttpRequest.newBuilder(URI.create(recordingsUrl + "/recordings")).build(),
          HttpResponse.BodyHandlers.ofString()
        ).body()
      );
      assertEquals(direct, listed);
      var catalogue = JSON.readTree(Files.readString(TestStack.ROOT.resolve(".fixtures/catalog.json")));
      assertEquals(5, listed.size());
      assertEquals(names(catalogue.get("recordings")), names(listed), "names come from the catalogue");
    }
  }

  @Test
  void reportsAnInfrastructureFailureWhenTheRecordingsServiceStops() throws Exception {
    try (var stopped = TestStack.recordingsService()) {
      stopped.start();
      var address = TestStack.baseUrl(stopped);
      stopped.stop();

      try (var context = TestStack.startApplication(address)) {
        var response = get(context, "/recordings");

        assertEquals(503, response.statusCode());
        var body = JSON.readTree(response.body());
        assertEquals("recordings_unavailable", body.get("code").asString());
        assertTrue(body.get("message").asString().length() > 0);
      }
    }
  }

  @Test
  @Timeout(30)
  void reportsAnInfrastructureFailureWhenTheRecordingsServiceDoesNotAnswer() throws Exception {
    try (var frozen = TestStack.recordingsService()) {
      frozen.start();
      frozen.getDockerClient().pauseContainerCmd(frozen.getContainerId()).exec();

      try (
        var context = TestStack.startApplication(TestStack.baseUrl(frozen), "--spring.http.clients.read-timeout=1s")
      ) {
        var response = get(context, "/recordings");

        assertEquals(503, response.statusCode());
        assertEquals("recordings_unavailable", JSON.readTree(response.body()).get("code").asString());
      }
    }
  }

  @Test
  @Timeout(30)
  void contentTimesOutWhenTheRealRecordingsServiceDoesNotSendHeaders() throws Exception {
    try (var frozen = TestStack.recordingsService()) {
      frozen.start();
      var docker = frozen.getDockerClient();
      docker.pauseContainerCmd(frozen.getContainerId()).exec();
      try (var context = TestStack.startApplication(TestStack.baseUrl(frozen), "--recordings.request-timeout=300ms")) {
        var client = context.getBean(local.signallab.recordings.RecordingsClient.class);
        var failure = assertThrows(local.signallab.recordings.RecordingsUnavailableException.class, () ->
          client.content(java.util.UUID.fromString("8069a3fc-629d-5e48-93ac-52639e88c09d"))
        );
        assertInstanceOf(java.net.http.HttpTimeoutException.class, failure.getCause());
      } finally {
        docker.unpauseContainerCmd(frozen.getContainerId()).exec();
      }
    }
  }
}
