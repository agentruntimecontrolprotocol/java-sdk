package dev.arcp.core.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Jackson {@link ObjectMapper} configured for ARCP wire I/O.
 *
 * <ul>
 *   <li>{@code USE_BIG_DECIMAL_FOR_FLOATS}: §9.6 budget arithmetic on {@code BigDecimal}.</li>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES=false}: §5.1 mandates ignoring unknown fields.</li>
 *   <li>{@link JavaTimeModule}: {@link java.time.Instant} for §9.5 expires_at.</li>
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS=false}: ISO-8601 on the wire.</li>
 *   <li>{@code Include.NON_NULL}: omit null fields globally.</li>
 * </ul>
 */
public final class ArcpMapper {

    private ArcpMapper() {}

    public static ObjectMapper create() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return m;
    }

    private static final ObjectMapper SHARED = create();

    /** Shared, immutable mapper for hot-path use. */
    public static ObjectMapper shared() {
        return SHARED;
    }
}
