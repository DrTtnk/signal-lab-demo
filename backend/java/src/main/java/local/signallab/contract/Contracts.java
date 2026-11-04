package local.signallab.contract;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class Contracts {

  private final JsonMapper json;
  private final ObjectNode schema;
  public final int windowFrames;
  private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
    SpecificationVersion.DRAFT_2020_12,
    builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
  );
  private final Map<String, Schema> definitions = new ConcurrentHashMap<>();

  public Contracts(JsonMapper json) {
    this.json = json;
    this.schema = (ObjectNode) read("contracts/v1/schema.json");
    this.windowFrames = read("contracts/v1/profile.json").get("windowFrames").asInt();
  }

  private JsonNode read(String resource) {
    try (InputStream stream = Contracts.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("Missing classpath resource " + resource);
      }
      return json.readTree(stream);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void validate(String definition, Object document) {
    var value = json.valueToTree(document);
    var compiled = definitions.computeIfAbsent(definition, name -> {
      var selected = schema.deepCopy();
      selected.put("$ref", "#/$defs/" + name);
      return registry.getSchema(json.writeValueAsString(selected));
    });
    var errors = compiled.validate((JsonNode) value);
    if (!errors.isEmpty()) {
      throw new ContractViolation(
        definition +
          " violates the contract: " +
          errors
            .stream()
            .map(error -> error.getMessage())
            .collect(Collectors.joining("; "))
      );
    }
  }
}
