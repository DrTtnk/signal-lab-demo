package local.signallab.engine;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("engine")
public record EngineProperties(Path path, Duration deadline, int workers) {}
