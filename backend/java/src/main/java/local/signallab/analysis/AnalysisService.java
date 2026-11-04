package local.signallab.analysis;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import local.signallab.contract.Contracts;
import local.signallab.contract.Wire;
import local.signallab.engine.EngineOutcome;
import local.signallab.engine.EngineProperties;
import local.signallab.engine.EngineRunner;
import local.signallab.queue.BatchQueue;
import local.signallab.queue.CapacityBusyException;
import local.signallab.queue.ConsumerProperties;
import local.signallab.recordings.Recording;
import local.signallab.recordings.RecordingsClient;
import local.signallab.web.InfrastructureFailure;
import org.springframework.stereotype.Service;

@Service
public record AnalysisService(
  Contracts contracts,
  RecordingsClient recordings,
  EngineRunner engine,
  AnalysisRepository repository,
  EngineProperties settings,
  ConsumerProperties consumer,
  BatchQueue queue
) {
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public Wire.BatchSnapshot submit(Wire.AnalysisRequest request) {
    var accepted = request.items().stream().map(this::accept).toList();
    var batchId = UUID.randomUUID();
    repository.createBatch(batchId, request.profileId(), now(), accepted);

    if (accepted.stream().noneMatch(item -> item instanceof AnalysisRepository.NewItem.Resolved)) {
      claimOrFail(batchId);
      finish(batchId);
      return snapshot(batchId);
    }
    var queued = snapshot(batchId);
    try {
      queue.publish(batchId);
    } catch (CapacityBusyException e) {
      repository.delete(batchId);
      throw e;
    } catch (InfrastructureFailure e) {
      repository.interruptBatch(batchId, e.error(), now());
      return snapshot(batchId);
    }
    return queued;
  }

  public void process(UUID batchId, boolean redelivered) {
    if (repository.claim(batchId, consumer.id())) {
      runClaimed(batchId);
      return;
    }
    if (redelivered && repository.state(batchId).equals(Optional.of("Running"))) {
      repository.interruptBatch(
        batchId,
        new Wire.InfrastructureError(
          Wire.InfrastructureCode.application_stopped,
          "The consumer stopped before the batch finished"
        ),
        now()
      );
    }
  }

  public Optional<Wire.BatchSnapshot> find(UUID batchId) {
    return repository.snapshot(batchId);
  }

  private void runClaimed(UUID batchId) {
    var profileId = repository.snapshot(batchId).orElseThrow().profileId();
    repository
      .pendingItems(batchId)
      .stream()
      .map(item -> attempt(profileId, item))
      .flatMap(Optional::stream)
      .findFirst()
      .ifPresentOrElse(failure -> repository.interruptBatch(batchId, failure.error(), now()), () -> finish(batchId));
  }

  private Optional<InfrastructureFailure> attempt(String profileId, AnalysisRepository.NewItem.Resolved item) {
    try {
      run(profileId, item);
      return Optional.empty();
    } catch (InfrastructureFailure e) {
      return Optional.of(e);
    }
  }

  private void claimOrFail(UUID batchId) {
    if (!repository.claim(batchId, consumer.id())) {
      throw new IllegalStateException("A new batch must be claimable: " + batchId);
    }
  }

  private AnalysisRepository.NewItem accept(Wire.RequestItem item) {
    var found = recordings.find(item.recordingId());
    found.ifPresent(recording -> contracts.validate("Recording", recording));
    return found
      .<AnalysisRepository.NewItem>map(recording ->
        new AnalysisRepository.NewItem.Resolved(item.itemId(), item.recordingId(), item.channels(), recording)
      )
      .orElseGet(() ->
        new AnalysisRepository.NewItem.Unknown(
          item.itemId(),
          item.recordingId(),
          item.channels(),
          new Wire.ItemError(
            Wire.ItemErrorCode.unknown_recording,
            "Recording " + item.recordingId() + " is not registered"
          )
        )
      );
  }

  private void run(String profileId, AnalysisRepository.NewItem.Resolved item) {
    repository.markRunning(item.itemId());
    var control = new Wire.EngineInput(
      Wire.CONTRACT_VERSION,
      profileId,
      item.itemId(),
      item.snapshot(),
      item.channels(),
      settings.workers()
    );
    try (var content = recordings.content(item.recordingId())) {
      switch (engine.run(control, content)) {
        case EngineOutcome.Succeeded succeeded -> repository.succeed(
          item.itemId(),
          succeeded.item().result(),
          succeeded.item().engineBuild(),
          succeeded.item().workers()
        );
        case EngineOutcome.Failed failed -> repository.fail(item.itemId(), failed.item().error());
      }
    } catch (java.io.IOException e) {
      throw new InfrastructureFailure(
        Wire.InfrastructureCode.recordings_unavailable,
        "Closing the recording stream failed",
        e
      );
    }
  }

  private void finish(UUID batchId) {
    var items = repository.snapshot(batchId).orElseThrow().items();
    var succeeded = items
      .stream()
      .filter(item -> item instanceof Wire.SucceededItem)
      .count();
    var state =
      succeeded == items.size()
        ? AnalysisRepository.Finished.COMPLETED
        : succeeded == 0
          ? AnalysisRepository.Finished.ALL_ITEMS_FAILED
          : AnalysisRepository.Finished.COMPLETED_WITH_ERRORS;
    repository.finish(batchId, state, now());
  }

  private Wire.BatchSnapshot snapshot(UUID batchId) {
    var snapshot = repository.snapshot(batchId).orElseThrow();
    contracts.validate("BatchSnapshot", snapshot);
    return snapshot;
  }
}
