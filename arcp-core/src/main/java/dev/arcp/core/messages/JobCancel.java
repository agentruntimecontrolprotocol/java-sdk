package dev.arcp.core.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobCancel(@Nullable String reason) implements Message {
    @JsonCreator
    public JobCancel(@JsonProperty("reason") @Nullable String reason) {
        this.reason = reason;
    }

    @Override
    public Type kind() {
        return Type.JOB_CANCEL;
    }
}
