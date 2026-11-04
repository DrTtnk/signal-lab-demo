package local.signallab.queue;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueueConfig {

  @Bean
  Queue batches(QueueProperties queue) {
    return QueueBuilder.durable(queue.name())
      .maxLength(queue.maxDepth())
      .overflow(QueueBuilder.Overflow.rejectPublish)
      .deadLetterExchange("")
      .deadLetterRoutingKey(queue.name() + ".dead")
      .build();
  }

  @Bean
  Queue deadLetters(QueueProperties queue) {
    return QueueBuilder.durable(queue.name() + ".dead").build();
  }
}
