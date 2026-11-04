package local.signallab.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("consumer")
public record ConsumerProperties(String id) {}
