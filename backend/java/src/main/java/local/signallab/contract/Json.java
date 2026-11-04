package local.signallab.contract;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class Json {

  private Json() {}

  public static JsonMapper mapper() {
    return JsonMapper.builder()
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
      .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
      .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
      .build();
  }
}
