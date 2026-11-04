package local.signallab.recordings;

import local.signallab.contract.Wire;
import local.signallab.web.InfrastructureFailure;

public class RecordingsUnavailableException extends InfrastructureFailure {

  public RecordingsUnavailableException(String reason) {
    super(Wire.InfrastructureCode.recordings_unavailable, reason);
  }

  public RecordingsUnavailableException(String reason, Throwable cause) {
    super(Wire.InfrastructureCode.recordings_unavailable, reason, cause);
  }
}
