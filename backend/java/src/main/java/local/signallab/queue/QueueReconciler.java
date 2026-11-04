package local.signallab.queue;

import java.time.Instant;
import java.util.UUID;
import local.signallab.analysis.AnalysisRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public record QueueReconciler(
  AnalysisRepository repository,
  BatchQueue queue,
  QueueProperties properties
) implements ApplicationRunner {
  @Override
  public void run(ApplicationArguments arguments) {
    repository
      .queuedBefore(Instant.now().minus(properties.reconcileAfter()))
      .stream()
      .takeWhile(this::publishedWhileNotFull)
      .forEach(batchId -> {});
  }

  private boolean publishedWhileNotFull(UUID batchId) {
    try {
      queue.publish(batchId);
      return true;
    } catch (CapacityBusyException e) {
      return false;
    }
  }
}
