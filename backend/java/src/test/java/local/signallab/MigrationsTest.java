package local.signallab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationsTest {

  private static final Map<String, Integer> APPLIED_CHECKSUMS = Map.of("1", -1151843788, "2", -1372197273);

  @Test
  void anAppliedMigrationFileIsNeverChanged() {
    var resolved = Flyway.configure()
      .locations("classpath:db/migration")
      .dataSource(TestStack.DATABASE.getJdbcUrl(), "analysis", TestStack.ANALYSIS_PASSWORD)
      .load()
      .info()
      .all();

    assertEquals(
      APPLIED_CHECKSUMS,
      java.util.Arrays.stream(resolved).collect(Collectors.toMap(m -> m.getVersion().toString(), m -> m.getChecksum()))
    );
  }
}
