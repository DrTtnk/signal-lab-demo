package local.signallab.recordings;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("recordings")
public record RecordingsProperties(String baseUrl, Duration connectTimeout) {}
