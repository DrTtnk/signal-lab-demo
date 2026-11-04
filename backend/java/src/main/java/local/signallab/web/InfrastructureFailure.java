package local.signallab.web;

import local.signallab.contract.Wire;

public class InfrastructureFailure extends RuntimeException {

  public final Wire.InfrastructureCode code;
  public final String reason;

  public InfrastructureFailure(Wire.InfrastructureCode code, String reason) {
    super(code + ": " + reason);
    this.code = code;
    this.reason = reason;
  }

  public InfrastructureFailure(Wire.InfrastructureCode code, String reason, Throwable cause) {
    super(code + ": " + reason, cause);
    this.code = code;
    this.reason = reason;
  }

  public Wire.InfrastructureError error() {
    return new Wire.InfrastructureError(code, reason);
  }
}
