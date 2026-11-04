package local.signallab;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

final class TestStack {

  static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
  static final String RECORDINGS_PASSWORD = "recordings-test-only";
  static final String ANALYSIS_PASSWORD = "analysis-test-only";
  static final HttpClient HTTP = HttpClient.newHttpClient();

  private static final Network NETWORK = Network.newNetwork();
  static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.1-alpine")
    .withNetwork(NETWORK)
    .withNetworkAliases("database")
    .withDatabaseName("signal_lab")
    .withUsername("postgres")
    .withPassword("postgres-test-only")
    .withEnv("RECORDINGS_DB_PASSWORD", RECORDINGS_PASSWORD)
    .withEnv("ANALYSIS_DB_PASSWORD", ANALYSIS_PASSWORD)
    .withCopyFileToContainer(
      MountableFile.forHostPath(ROOT.resolve("docker/database/init.sh"), 0755),
      "/docker-entrypoint-initdb.d/init.sh"
    );
  static final GenericContainer<?> RECORDINGS = recordingsService();
  static final String BROKER_USER = "signal";
  static final String BROKER_PASSWORD = "broker-test-only";
  static final RabbitMQContainer BROKER = broker();

  static {
    DATABASE.start();
    RECORDINGS.start();
    BROKER.start();
  }

  private TestStack() {}

  @SuppressWarnings("resource")
  static GenericContainer<?> recordingsService() {
    return new GenericContainer<>("signal-lab-recordings")
      .withNetwork(NETWORK)
      .withEnv(
        "RECORDINGS_DATABASE_URL",
        "postgresql+psycopg://recordings:" + RECORDINGS_PASSWORD + "@database:5432/signal_lab"
      )
      .withEnv("RECORDINGS_DIR", "/data")
      .withEnv("CONTRACTS_DIR", "/app/contracts/v1")
      .withFileSystemBind(ROOT.resolve(".fixtures").toString(), "/data", BindMode.READ_ONLY)
      .withExposedPorts(8000)
      .waitingFor(Wait.forHttp("/health").forStatusCode(200));
  }

  static org.awaitility.core.ConditionFactory await() {
    return org.awaitility.Awaitility.await()
      .dontCatchUncaughtExceptions()
      .atMost(java.time.Duration.ofSeconds(30))
      .pollInterval(java.time.Duration.ofMillis(100));
  }

  static RabbitMQContainer broker() {
    return new RabbitMQContainer("rabbitmq:4.1.8-alpine").withAdminUser(BROKER_USER).withAdminPassword(BROKER_PASSWORD);
  }

  static String baseUrl(GenericContainer<?> container) {
    return "http://" + container.getHost() + ":" + container.getMappedPort(8000);
  }

  static final Path ENGINE = ROOT.resolve("backend/rust/target/debug/signal-lab-engine");

  static ConfigurableApplicationContext startApplication(String recordingsBaseUrl, String... overrides) {
    var properties = new LinkedHashMap<String, String>();
    properties.put("server.port", "0");
    properties.put("spring.datasource.url", DATABASE.getJdbcUrl());
    properties.put("spring.datasource.username", "analysis");
    properties.put("spring.datasource.password", ANALYSIS_PASSWORD);
    properties.put("recordings.base-url", recordingsBaseUrl);
    properties.put("engine.path", ENGINE.toString());
    properties.put("engine.deadline", "30s");
    properties.put("engine.workers", "1");
    properties.put("consumer.id", "test-consumer");
    properties.put("spring.rabbitmq.host", BROKER.getHost());
    properties.put("spring.rabbitmq.port", String.valueOf(BROKER.getAmqpPort()));
    properties.put("spring.rabbitmq.username", BROKER_USER);
    properties.put("spring.rabbitmq.password", BROKER_PASSWORD);
    properties.put("analysis.queue.reconcile-after", "1h");
    properties.put("analysis.queue.name", "analysis.batches." + UUID.randomUUID());
    for (var override : overrides) {
      var separator = override.indexOf('=');
      properties.put(override.substring(2, separator), override.substring(separator + 1));
    }
    var arguments = properties
      .entrySet()
      .stream()
      .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
      .toArray(String[]::new);
    return new SpringApplicationBuilder(SignalLabApplication.class).run(arguments);
  }
}
