package local.signallab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import local.signallab.web.HealthController;
import org.junit.jupiter.api.Test;

class HealthControllerTest {

  @Test
  void reportsReadiness() {
    var response = new HealthController().health();

    assertEquals(new HealthController.HealthResponse("ready"), response);
  }
}
