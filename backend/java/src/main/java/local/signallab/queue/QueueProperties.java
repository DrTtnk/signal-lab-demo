package local.signallab.queue;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("analysis.queue")
public record QueueProperties(String name, int maxDepth, Duration confirmTimeout, Duration reconcileAfter) {}
