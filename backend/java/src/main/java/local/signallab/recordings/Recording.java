package local.signallab.recordings;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.UUID;
import local.signallab.utils.Jsonb;

public record Recording(
  UUID recordingId,
  String name,
  String sha256,
  long byteLength,
  long frameCount,
  int channelCount,
  int sampleRateHz,
  String encoding,
  Provenance provenance
) implements Jsonb {
  @JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind",
    visible = true
  )
  @JsonSubTypes({
    @JsonSubTypes.Type(value = FixedPattern.class, name = "FixedPattern"),
    @JsonSubTypes.Type(value = Seeded.class, name = "Seeded"),
  })
  public sealed interface Provenance permits FixedPattern, Seeded {
    String kind();

    String recipeVersion();
  }

  public record FixedPattern(String kind, String recipeVersion, String patternName) implements Provenance {}

  public record Seeded(String kind, String recipeVersion, long seed) implements Provenance {}
}
