package local.signallab.analysis;

import java.util.UUID;
import local.signallab.contract.Wire;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public record AnalysisController(AnalysisService analyses) {
  @PostMapping("/analyses")
  public ResponseEntity<Wire.BatchSnapshot> submit(@RequestBody Wire.AnalysisRequest request) {
    var batch = analyses.submit(request);
    var status = switch (batch) {
      case Wire.QueuedBatch _ -> HttpStatus.ACCEPTED;
      case Wire.InterruptedBatch _ -> HttpStatus.SERVICE_UNAVAILABLE;
      case Wire.RunningBatch _ -> HttpStatus.OK;
      case Wire.CompletedBatch _ -> HttpStatus.OK;
      case Wire.CompletedWithErrorsBatch _ -> HttpStatus.OK;
      case Wire.AllItemsFailedBatch _ -> HttpStatus.OK;
    };
    return ResponseEntity.status(status).body(batch);
  }

  @GetMapping("/analyses/{batchId}")
  public Wire.BatchSnapshot get(@PathVariable UUID batchId) {
    return analyses.find(batchId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown batch"));
  }
}
