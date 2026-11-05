package local.signallab.queue;

import java.time.Instant;
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
    var cutoff = Instant.now().minus(properties.reconcileAfter());
    for (var batchId : repository.queuedBefore(cutoff)) {
      try {
        queue.publish(batchId);
      } catch (CapacityBusyException e) {
        break;
      }
    }
  }
}
