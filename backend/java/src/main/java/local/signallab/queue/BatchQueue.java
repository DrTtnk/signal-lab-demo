package local.signallab.queue;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import local.signallab.contract.Wire;
import local.signallab.web.InfrastructureFailure;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public record BatchQueue(RabbitTemplate template, QueueProperties queue) {
  public void publish(UUID batchId) {
    var correlation = new CorrelationData(batchId.toString());
    try {
      template.convertAndSend("", queue.name(), batchId.toString(), correlation);
      var confirm = correlation.getFuture().get(queue.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (!confirm.ack()) {
        throw new CapacityBusyException("The batch queue is full");
      }
    } catch (AmqpException | ExecutionException e) {
      throw new InfrastructureFailure(Wire.InfrastructureCode.broker_unavailable, "The message broker failed", e);
    } catch (TimeoutException e) {
      throw new InfrastructureFailure(
        Wire.InfrastructureCode.broker_unavailable,
        "The message broker did not confirm the batch in time",
        e
      );
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InfrastructureFailure(Wire.InfrastructureCode.application_stopped, "Publishing was interrupted", e);
    }
  }
}
