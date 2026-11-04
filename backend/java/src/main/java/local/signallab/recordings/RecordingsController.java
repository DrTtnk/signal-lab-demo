package local.signallab.recordings;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public record RecordingsController(RecordingsClient recordings) {
  @GetMapping("/recordings")
  public List<Recording> list() {
    return recordings.list();
  }
}
