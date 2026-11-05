package local.signallab.recordings;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import local.signallab.contract.Wire;
import local.signallab.web.InfrastructureFailure;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class RecordingsClient {

  private final RestClient client;
  private final HttpClient stream;
  private final String baseUrl;
  private final Duration requestTimeout;

  public RecordingsClient(RestClient.Builder builder, RecordingsProperties properties) {
    this.baseUrl = properties.baseUrl();
    this.requestTimeout = properties.requestTimeout();
    this.client = builder
      .baseUrl(properties.baseUrl())
      .defaultStatusHandler(HttpStatusCode::is5xxServerError, (request, response) -> {
        throw new RecordingsUnavailableException("Recordings service answered " + response.getStatusCode());
      })
      .build();
    this.stream = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
  }

  public List<Recording> list() {
    try {
      var body = client.get().uri("/recordings").retrieve().body(new ParameterizedTypeReference<List<Recording>>() {});
      return Objects.requireNonNull(body, "Recordings service returned no body");
    } catch (ResourceAccessException e) {
      throw new RecordingsUnavailableException("Recordings service is unreachable", e);
    }
  }

  public Optional<Recording> find(UUID recordingId) {
    try {
      return client
        .get()
        .uri("/recordings/{id}", recordingId)
        .exchange((request, response) -> {
          var status = response.getStatusCode();
          if (status.value() == 404) {
            return Optional.<Recording>empty();
          }
          if (status.is5xxServerError()) {
            throw new RecordingsUnavailableException("Recordings service answered " + status);
          }
          if (!status.is2xxSuccessful()) {
            throw new IllegalStateException("Unexpected recordings answer " + status);
          }
          return Optional.of(Objects.requireNonNull(response.bodyTo(Recording.class)));
        });
    } catch (ResourceAccessException e) {
      throw new RecordingsUnavailableException("Recordings service is unreachable", e);
    }
  }

  public InputStream content(UUID recordingId) {
    var request = HttpRequest.newBuilder(URI.create(baseUrl + "/recordings/" + recordingId + "/content"))
      .timeout(requestTimeout)
      .build();
    try {
      var response = stream.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        response.body().close();
        throw new RecordingsUnavailableException(
          "Recordings service answered " + response.statusCode() + " for the content"
        );
      }
      return response.body();
    } catch (IOException e) {
      throw new RecordingsUnavailableException("Recordings service is unreachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InfrastructureFailure(
        Wire.InfrastructureCode.application_stopped,
        "Interrupted while reading content",
        e
      );
    }
  }
}
