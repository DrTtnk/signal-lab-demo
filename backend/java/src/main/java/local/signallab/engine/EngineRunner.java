package local.signallab.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import local.signallab.contract.ContractViolation;
import local.signallab.contract.Contracts;
import local.signallab.contract.Wire;
import local.signallab.web.InfrastructureFailure;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public record EngineRunner(EngineProperties settings, Contracts contracts, JsonMapper json) {
  private static final int MAX_OUTPUT_BYTES = 1 << 20;
  private static final int MAX_DIAGNOSTIC_CHARS = 300;

  public EngineOutcome run(Wire.EngineInput control, InputStream recording) {
    contracts.validate("EngineInput", control);
    var process = start();
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var stdout = executor.submit(() -> readBounded(process.getInputStream()));
      var stderr = executor.submit(() -> readBounded(process.getErrorStream()));
      var stdin = executor.submit(() -> {
        try (var out = process.getOutputStream()) {
          out.write((json.writeValueAsString(control) + "\n").getBytes(StandardCharsets.UTF_8));
          recording.transferTo(out);
        }
        return true;
      });
      if (!process.waitFor(settings.deadline().toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly().waitFor();
        throw new InfrastructureFailure(
          Wire.InfrastructureCode.deadline_exceeded,
          "Engine did not finish within " + settings.deadline()
        );
      }
      var exit = process.exitValue();
      var output = new String(stdout.get(), StandardCharsets.UTF_8);
      var diagnostics = new String(stderr.get(), StandardCharsets.UTF_8);
      boolean inputDelivered = stdin.isDone() && succeeded(stdin);
      return interpret(control, exit, output, diagnostics, inputDelivered);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new InfrastructureFailure(
        Wire.InfrastructureCode.application_stopped,
        "Interrupted while running the engine",
        e
      );
    } catch (ExecutionException e) {
      process.destroyForcibly();
      throw new InfrastructureFailure(Wire.InfrastructureCode.engine_protocol_error, "Engine streams failed", e);
    } finally {
      executor.shutdownNow();
    }
  }

  private static boolean succeeded(java.util.concurrent.Future<Boolean> future) {
    try {
      return future.get();
    } catch (ExecutionException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private Process start() {
    try {
      return new ProcessBuilder(settings.path().toString(), "analyse").start();
    } catch (IOException e) {
      throw new InfrastructureFailure(
        Wire.InfrastructureCode.engine_unavailable,
        "Engine cannot start: " + settings.path(),
        e
      );
    }
  }

  private static byte[] readBounded(InputStream stream) throws IOException {
    var bytes = stream.readNBytes(MAX_OUTPUT_BYTES + 1);
    if (bytes.length > MAX_OUTPUT_BYTES) {
      throw new IOException("Engine stream exceeds " + MAX_OUTPUT_BYTES + " bytes");
    }
    return bytes;
  }

  private EngineOutcome interpret(
    Wire.EngineInput control,
    int exit,
    String output,
    String diagnostics,
    boolean inputDelivered
  ) {
    if (exit != 0 && exit != 2) {
      throw protocol(
        "Engine exited with " +
          exit +
          ": " +
          diagnostics.substring(0, Math.min(diagnostics.length(), MAX_DIAGNOSTIC_CHARS))
      );
    }
    if (exit == 0 && !inputDelivered) {
      throw protocol("Engine succeeded without all input");
    }
    var lines = output.split("\n", -1);
    if (lines.length != 2 || !lines[1].isEmpty()) {
      throw protocol("Engine must write exactly one line");
    }
    try {
      JsonNode answer = json.readTree(lines[0]);
      contracts.validate("EngineOutput", answer);
      return verified(control, json.treeToValue(answer, Wire.ItemSnapshot.class), exit);
    } catch (ContractViolation e) {
      throw new InfrastructureFailure(Wire.InfrastructureCode.engine_protocol_error, e.detail, e);
    } catch (JacksonException e) {
      throw new InfrastructureFailure(
        Wire.InfrastructureCode.engine_protocol_error,
        "Engine answer is not typed: " + e.getOriginalMessage(),
        e
      );
    }
  }

  private static InfrastructureFailure protocol(String reason) {
    return new InfrastructureFailure(Wire.InfrastructureCode.engine_protocol_error, reason);
  }

  private EngineOutcome verified(Wire.EngineInput control, Wire.ItemSnapshot answer, int exit) {
    expect(answer.itemId().equals(control.itemId()), "item identity");
    expect(answer.recordingId().equals(control.recording().recordingId()), "recording identity");
    return switch (answer) {
      case Wire.SucceededItem succeeded when exit == 0 -> {
        verifySuccess(control, succeeded);
        yield new EngineOutcome.Succeeded(succeeded);
      }
      case Wire.FailedItem failed when exit == 2 -> new EngineOutcome.Failed(failed);
      default -> throw new ContractViolation("Engine answer does not match its input: state and exit status");
    };
  }

  private void verifySuccess(Wire.EngineInput control, Wire.SucceededItem answer) {
    var recording = control.recording();
    expect(answer.profileId().equals(control.profileId()), "profile");
    expect(answer.workers() == control.workers(), "worker setting");
    expect(answer.recordingSha256().equals(recording.sha256()), "digest");
    expect(answer.recordingProvenance().equals(recording.provenance()), "provenance");
    var tail = recording.frameCount() % contracts.windowFrames;
    var result = answer.result();
    expect(result.processedFrames() == recording.frameCount() - tail, "processed frames");
    expect(result.ignoredTailFrames() == tail, "ignored tail");
    expect(result.channels().size() == control.channels().size(), "channel count");
    expect(
      IntStream.range(0, control.channels().size()).allMatch(
        index -> result.channels().get(index).channelIndex() == control.channels().get(index)
      ),
      "channel order"
    );
  }

  private static void expect(boolean condition, String what) {
    if (!condition) {
      throw new ContractViolation("Engine answer does not match its input: " + what);
    }
  }
}
