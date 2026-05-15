package dev.arcp.core.agents;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Agent reference parsed from the wire form {@code name} or {@code name@version}.
 * Grammar per spec §7.5:
 *
 * <pre>
 * agent   ::= name | name "@" version
 * name    ::= [a-z0-9][a-z0-9._-]*
 * version ::= [a-zA-Z0-9.+_-]+
 * </pre>
 */
public record AgentRef(String name, @Nullable String version) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern VERSION = Pattern.compile("[a-zA-Z0-9.+_\\-]+");

    public AgentRef {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid agent name: " + name);
        }
        if (version != null && !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("invalid agent version: " + version);
        }
    }

    public Optional<String> versionOpt() {
        return Optional.ofNullable(version);
    }

    @JsonCreator
    public static AgentRef parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        int at = raw.indexOf('@');
        if (at < 0) {
            return new AgentRef(raw, null);
        }
        return new AgentRef(raw.substring(0, at), raw.substring(at + 1));
    }

    @JsonValue
    public String wire() {
        return version == null ? name : name + "@" + version;
    }

    @Override
    public String toString() {
        return wire();
    }
}
