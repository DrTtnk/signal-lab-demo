package local.signallab.web;

import local.signallab.contract.Wire;
import local.signallab.queue.CapacityBusyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class InfrastructureErrorAdvice {

  public record RejectedRequest(String code, String message) {}

  @ExceptionHandler(InfrastructureFailure.class)
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  public Wire.InfrastructureError infrastructure(InfrastructureFailure e) {
    return e.error();
  }

  @ExceptionHandler(CapacityBusyException.class)
  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  public RejectedRequest busy(CapacityBusyException e) {
    return new RejectedRequest("capacity_busy", e.detail);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public RejectedRequest unreadable(HttpMessageNotReadableException e) {
    return new RejectedRequest("invalid_request", String.valueOf(e.getMessage()));
  }
}
