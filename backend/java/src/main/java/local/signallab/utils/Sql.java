package local.signallab.utils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.argument.ArgumentFactory;
import org.jdbi.v3.core.mapper.reflect.CaseInsensitiveColumnNameMatcher;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.jdbi.v3.core.mapper.reflect.ReflectionMappers;
import org.jdbi.v3.core.mapper.reflect.SnakeCaseColumnNameMatcher;
import org.jdbi.v3.core.statement.SqlStatement;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.postgresql.util.PGobject;
import tools.jackson.databind.json.JsonMapper;

/**
 * Raw SQL with {@code :named} parameters. A parameter source is a {@link Params} bag or a record
 * ({@code :id} binds {@code id()}). Several sources bind in order onto one statement. A bound value that
 * is a {@link Map}, a {@link Collection} or a {@link Jsonb} is serialized whole into a {@code jsonb}
 * parameter; scalars bind natively. Rows map to records: scalar columns by name ({@code my_value} to
 * {@code myValue}), a {@code json}/{@code jsonb} column to a nested record or collection.
 */
public final class Sql {

  private Sql() {}

  public static Jdbi jdbi(DataSource dataSource, JsonMapper mapper) {
    var jdbi = Jdbi.create(dataSource);
    configure(jdbi, mapper);
    return jdbi;
  }

  public static void configure(Jdbi jdbi, JsonMapper mapper) {
    jdbi.installPlugin(new PostgresPlugin());
    jdbi.registerColumnMapper(new JsonColumnMapperFactory(mapper));
    ArgumentFactory jsonb = (type, value, config) -> {
      if (value instanceof Map || value instanceof Collection || value instanceof Jsonb) {
        var json = mapper.writeValueAsString(value);
        Argument argument = (position, statement, context) -> {
          var object = new PGobject();
          object.setType("jsonb");
          object.setValue(json);
          statement.setObject(position, object);
        };
        return Optional.of(argument);
      }
      return Optional.empty();
    };
    jdbi.registerArgument(jsonb);
    jdbi
      .getConfig(ReflectionMappers.class)
      .setColumnNameMatchers(List.of(new CaseInsensitiveColumnNameMatcher(), new SnakeCaseColumnNameMatcher()));
  }

  public static <O> List<O> query(Handle handle, String sql, Class<O> type, Object... sources) {
    return bind(handle.createQuery(sql), sources).map(ConstructorMapper.of(type)).list();
  }

  public static <O> Optional<O> queryOne(Handle handle, String sql, Class<O> type, Object... sources) {
    return bind(handle.createQuery(sql), sources).map(ConstructorMapper.of(type)).findOne();
  }

  public static <O> Optional<O> queryScalar(Handle handle, String sql, Class<O> type, Object... sources) {
    return bind(handle.createQuery(sql), sources).mapTo(type).findOne();
  }

  public static <O> List<O> queryScalarList(Handle handle, String sql, Class<O> type, Object... sources) {
    return bind(handle.createQuery(sql), sources).mapTo(type).list();
  }

  public static int execute(Handle handle, String sql, Object... sources) {
    return bind(handle.createUpdate(sql), sources).execute();
  }

  private static <S extends SqlStatement<S>> S bind(S statement, Object... sources) {
    for (var source : sources) {
      switch (source) {
        case Params params -> params.values.forEach(statement::bind);
        case Map<?, ?> ignored -> throw new IllegalArgumentException(
          "Sql parameters must be Sql.params(...) or a record, not a Map"
        );
        default -> {
          if (!source.getClass().isRecord()) {
            throw new IllegalArgumentException("Sql parameters must be Sql.params(...) or a record");
          }
          statement.bindMethods(source);
        }
      }
    }
    return statement;
  }

  public static final class Params {

    private final Map<String, Object> values = new LinkedHashMap<>();

    private Params() {}
  }

  public static Params params(Object... keyValues) {
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("params() needs key, value pairs");
    }
    var params = new Params();
    for (var index = 0; index < keyValues.length; index += 2) {
      params.values.put((String) keyValues[index], keyValues[index + 1]);
    }
    return params;
  }
}
