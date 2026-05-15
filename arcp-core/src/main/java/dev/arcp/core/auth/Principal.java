package dev.arcp.core.auth;

import java.util.Objects;

public record Principal(String id) {
    public Principal {
        Objects.requireNonNull(id, "id");
    }
}
