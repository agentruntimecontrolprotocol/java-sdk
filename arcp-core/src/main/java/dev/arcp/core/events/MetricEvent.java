package dev.arcp.core.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * §8.2 {@code metric} event body: a numeric measurement emitted by the job.
 *
 * @param name metric name
 * @param value measured value
 * @param unit unit label, or {@code null}
 * @param dimensions key/value dimensions qualifying the measurement, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricEvent(
    String name, BigDecimal value, @Nullable String unit, @Nullable Map<String, String> dimensions)
    implements EventBody {

  /** Canonical constructor; {@code dimensions} is defensively copied. */
  @JsonCreator
  public MetricEvent(
      @JsonProperty("name") String name,
      @JsonProperty("value") BigDecimal value,
      @JsonProperty("unit") @Nullable String unit,
      @JsonProperty("dimensions") @Nullable Map<String, String> dimensions) {
    this.name = name;
    this.value = value;
    this.unit = unit;
    this.dimensions = dimensions == null ? null : Map.copyOf(dimensions);
  }

  @Override
  public Kind kind() {
    return Kind.METRIC;
  }
}
