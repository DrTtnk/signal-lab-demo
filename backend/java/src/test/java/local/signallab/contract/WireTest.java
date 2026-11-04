package local.signallab.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import local.signallab.recordings.Recording;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WireTest {

  private static final JsonMapper JSON = Json.mapper();
  private static final Contracts CONTRACTS = new Contracts(JSON);
  private static final String RECORDING = """
  {"recordingId":"8069a3fc-629d-5e48-93ac-52639e88c09d","name":"dc-mono","sha256":"%s",
   "byteLength":8236,"frameCount":4096,"channelCount":1,"sampleRateHz":48000,
   "encoding":"wav_pcm_s16le",
   "provenance":{"kind":"FixedPattern","recipeVersion":"integer-patterns-v1","patternName":"dc-mono"}}
  """.formatted("a".repeat(64));

  private static String spectrum() {
    return IntStream.range(0, 2049)
      .mapToObj(bin -> bin == 0 ? "1.0" : "0.0")
      .collect(Collectors.joining(",", "[", "]"));
  }

  private static String succeeded(String peak) {
    return """
    {"itemId":"11111111-1111-4111-8111-111111111111","recordingId":"8069a3fc-629d-5e48-93ac-52639e88c09d",
     "state":"Succeeded","recordingSha256":"%s","profileId":"pcm16-48k-hann4096-v1",
     "engineBuild":"0.1.0+scalar-v1","workers":1,
     "recordingProvenance":{"kind":"FixedPattern","recipeVersion":"integer-patterns-v1","patternName":"dc-mono"},
     "result":{"processedFrames":4096,"ignoredTailFrames":0,"processedWindows":1,
      "channels":[{"channelIndex":0,"rms":0.25,"meanPsd":%s,"bandPowers":[0.1,0.0,0.0],"peak":%s}]}}
    """.formatted("a".repeat(64), spectrum(), peak);
  }

  private static <T> void roundTrips(String definition, String text, Class<T> type) throws Exception {
    var original = JSON.readTree(text);
    CONTRACTS.validate(definition, original);
    var record = JSON.readValue(text, type);
    assertEquals(original, JSON.readTree(JSON.writeValueAsString(record)), definition + " round trip");
  }

  @Test
  void aRecordingRoundTripsWithTheCanonicalSchema() throws Exception {
    roundTrips("Recording", RECORDING, Recording.class);
  }

  @Test
  void itemStatesRoundTripWithTheCanonicalSchema() throws Exception {
    roundTrips("ItemSnapshot", succeeded("{\"kind\":\"Peak\",\"bin\":0,\"frequencyHz\":0.0}"), Wire.ItemSnapshot.class);
    roundTrips("ItemSnapshot", succeeded("{\"kind\":\"NoPeak\"}"), Wire.ItemSnapshot.class);
    var id =
      "\"itemId\":\"11111111-1111-4111-8111-111111111111\",\"recordingId\":\"8069a3fc-629d-5e48-93ac-52639e88c09d\"";
    roundTrips("ItemSnapshot", "{" + id + ",\"state\":\"Pending\"}", Wire.ItemSnapshot.class);
    roundTrips("ItemSnapshot", "{" + id + ",\"state\":\"Running\"}", Wire.ItemSnapshot.class);
    roundTrips(
      "ItemSnapshot",
      "{" + id + ",\"state\":\"Failed\",\"error\":{\"code\":\"digest_mismatch\",\"message\":\"x\"}}",
      Wire.ItemSnapshot.class
    );
    roundTrips(
      "ItemSnapshot",
      "{" + id + ",\"state\":\"Interrupted\",\"error\":{\"code\":\"engine_unavailable\",\"message\":\"x\"}}",
      Wire.ItemSnapshot.class
    );
    roundTrips(
      "ItemSnapshot",
      "{" + id + ",\"state\":\"NotRun\",\"reason\":\"batch_interrupted\"}",
      Wire.ItemSnapshot.class
    );
  }

  @Test
  void batchStatesRoundTripWithTheCanonicalSchema() throws Exception {
    var head =
      "\"contractVersion\":\"1\",\"batchId\":\"33333333-3333-4333-8333-333333333333\",\"profileId\":\"pcm16-48k-hann4096-v1\",\"createdAt\":\"2026-11-03T06:40:00.123456Z\"";
    var pending =
      "{\"itemId\":\"11111111-1111-4111-8111-111111111111\",\"recordingId\":\"8069a3fc-629d-5e48-93ac-52639e88c09d\",\"state\":\"Pending\"}";
    roundTrips(
      "BatchSnapshot",
      "{" + head + ",\"state\":\"Queued\",\"items\":[" + pending + "]}",
      Wire.BatchSnapshot.class
    );
    var item = JSON.readTree(succeeded("{\"kind\":\"NoPeak\"}")).toString();
    roundTrips(
      "BatchSnapshot",
      "{" + head + ",\"state\":\"Completed\",\"finishedAt\":\"2026-11-03T06:40:01.500Z\",\"items\":[" + item + "]}",
      Wire.BatchSnapshot.class
    );
    var interrupted =
      "{\"itemId\":\"11111111-1111-4111-8111-111111111111\",\"recordingId\":\"8069a3fc-629d-5e48-93ac-52639e88c09d\",\"state\":\"Interrupted\",\"error\":{\"code\":\"engine_unavailable\",\"message\":\"x\"}}";
    roundTrips(
      "BatchSnapshot",
      "{" +
        head +
        ",\"state\":\"Interrupted\",\"finishedAt\":\"2026-11-03T06:40:01.500Z\",\"error\":{\"code\":\"engine_unavailable\",\"message\":\"x\"},\"items\":[" +
        interrupted +
        "]}",
      Wire.BatchSnapshot.class
    );
  }

  @Test
  void aSucceededItemParsesToTheTypedPeak() throws Exception {
    var item = JSON.readValue(succeeded("{\"kind\":\"Peak\",\"bin\":7,\"frequencyHz\":82.0}"), Wire.ItemSnapshot.class);

    var succeeded = assertInstanceOf(Wire.SucceededItem.class, item);
    var peak = assertInstanceOf(Wire.Located.class, succeeded.result().channels().get(0).peak());
    assertEquals(7, peak.bin());
  }

  @Test
  void anAnalysisRequestParsesAndAnItemIsIdentifiedOnce() throws Exception {
    var item =
      "{\"itemId\":\"11111111-1111-4111-8111-111111111111\",\"recordingId\":\"8069a3fc-629d-5e48-93ac-52639e88c09d\",\"channels\":[1,0]}";
    var text = "{\"contractVersion\":\"1\",\"profileId\":\"pcm16-48k-hann4096-v1\",\"items\":[" + item + "]}";
    CONTRACTS.validate("AnalysisRequest", JSON.readTree(text));
    assertEquals(List.of(1, 0), JSON.readValue(text, Wire.AnalysisRequest.class).items().get(0).channels());

    var duplicate = text.replace("]}", "," + item + "]}");
    assertThrows(JacksonException.class, () -> JSON.readValue(duplicate, Wire.AnalysisRequest.class));
  }

  @Test
  void theMapperRefusesWhatTheContractRefuses() {
    var good = succeeded("{\"kind\":\"NoPeak\"}");
    var cases = List.of(
      good.replace("\"state\":\"Succeeded\"", "\"state\":\"Succeeded\",\"extra\":1"),
      good.replace("\"workers\":1", "\"workers\":null"),
      good.replace("\"engineBuild\":\"0.1.0+scalar-v1\",", ""),
      good.replace("\"rms\":0.25", "\"rms\":-1"),
      good.replace("\"channelIndex\":0", "\"channelIndex\":4"),
      good.replace("\"bandPowers\":[0.1,0.0,0.0]", "\"bandPowers\":[0.1]"),
      good.replace("{\"kind\":\"NoPeak\"}", "{\"kind\":\"Other\"}"),
      good.replace("\"state\":\"Succeeded\"", "\"state\":\"Almost\""),
      good + " {}"
    );
    for (var text : cases) {
      assertThrows(JacksonException.class, () -> JSON.readValue(text, Wire.ItemSnapshot.class), text);
    }
  }

  @Test
  void channelSelectionsAreClosed() {
    var item =
      "{\"itemId\":\"11111111-1111-4111-8111-111111111111\",\"recordingId\":\"8069a3fc-629d-5e48-93ac-52639e88c09d\",\"channels\":";
    for (var channels : List.of("[]", "[0,0]", "[4]", "[-1]", "[0,1,2,3,0]", "[0.5]", "null")) {
      assertThrows(
        JacksonException.class,
        () -> JSON.readValue(item + channels + "}", Wire.RequestItem.class),
        channels
      );
    }
  }

  @Test
  void anAbsentPeakWritesNoPositionAndIsStillAPeakKind() throws Exception {
    JsonNode node = JSON.valueToTree(new Wire.Absent());
    assertEquals("NoPeak", node.get("kind").asString());
    assertEquals(1, node.size());
  }
}
