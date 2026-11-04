package local.signallab.contract;

public class ContractViolation extends RuntimeException {

  public final String detail;

  public ContractViolation(String detail) {
    super(detail);
    this.detail = detail;
  }
}
