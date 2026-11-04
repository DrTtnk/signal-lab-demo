package local.signallab.engine;

import local.signallab.contract.Wire;

public sealed interface EngineOutcome {
  record Succeeded(Wire.SucceededItem item) implements EngineOutcome {}

  record Failed(Wire.FailedItem item) implements EngineOutcome {}
}
