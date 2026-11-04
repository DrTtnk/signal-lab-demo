package local.signallab.queue;

public class CapacityBusyException extends RuntimeException {

  public final String detail;

  public CapacityBusyException(String detail) {
    super(detail);
    this.detail = detail;
  }
}
