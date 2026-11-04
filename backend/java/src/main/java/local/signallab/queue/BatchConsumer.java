package local.signallab.queue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import local.signallab.analysis.AnalysisService;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public record BatchConsumer(AnalysisService analyses) {
  @RabbitListener(queues = "${analysis.queue.name}")
  void consume(Message message) {
    UUID batchId;
    try {
      batchId = UUID.fromString(new String(message.getBody(), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      throw new AmqpRejectAndDontRequeueException("The message is not a batch identifier", e);
    }
    var redelivered = Boolean.TRUE.equals(message.getMessageProperties().isRedelivered());
    analyses.process(batchId, redelivered);
  }
}
