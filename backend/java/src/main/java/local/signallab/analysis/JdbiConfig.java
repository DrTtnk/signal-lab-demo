package local.signallab.analysis;

import javax.sql.DataSource;
import local.signallab.utils.Sql;
import org.jdbi.v3.core.Jdbi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JdbiConfig {

  @Bean
  Jdbi jdbi(DataSource dataSource, JsonMapper mapper) {
    return Sql.jdbi(dataSource, mapper);
  }
}
