package local.signallab.utils;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.Set;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.mapper.ColumnMapperFactory;
import tools.jackson.databind.json.JsonMapper;

public final class JsonColumnMapperFactory implements ColumnMapperFactory {

  private static final Set<Class<?>> SCALARS = Set.of(
    String.class,
    Boolean.class,
    Character.class,
    Byte.class,
    Short.class,
    Integer.class,
    Long.class,
    Float.class,
    Double.class,
    java.math.BigDecimal.class,
    java.math.BigInteger.class,
    java.util.UUID.class,
    java.time.LocalDate.class,
    java.time.LocalDateTime.class,
    java.time.LocalTime.class,
    java.time.OffsetDateTime.class,
    java.time.Instant.class,
    java.time.ZonedDateTime.class
  );

  private final JsonMapper mapper;

  public JsonColumnMapperFactory(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<ColumnMapper<?>> build(Type type, ConfigRegistry config) {
    var raw = rawType(type);
    if (raw.isEmpty() || !isJsonMapped(raw.get())) {
      return Optional.empty();
    }
    var javaType = mapper.constructType(type);
    return Optional.of((rs, column, context) -> {
      var json = rs.getString(column);
      return json == null ? null : mapper.readValue(json, javaType);
    });
  }

  private static boolean isJsonMapped(Class<?> raw) {
    if (raw.isPrimitive() || raw.isEnum() || raw.isArray()) {
      return false;
    }
    return !SCALARS.contains(raw);
  }

  private static Optional<Class<?>> rawType(Type type) {
    if (type instanceof Class<?> c) {
      return Optional.of(c);
    }
    if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
      return Optional.of(c);
    }
    return Optional.empty();
  }
}
