package dev.arcp.core.messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.core.wire.Envelope;

/**
 * Encode/decode helpers binding a polymorphic {@link Message} to the envelope's
 * {@code payload} {@link JsonNode}. The {@code type} string is the canonical
 * discriminator on the wire.
 */
public final class Messages {

    private Messages() {}

    public static Message decode(ObjectMapper mapper, Envelope envelope) {
        Message.Type type = Message.Type.fromWire(envelope.type());
        JsonNode payload = envelope.payload();
        return switch (type) {
            case SESSION_HELLO -> mapper.convertValue(payload, SessionHello.class);
            case SESSION_WELCOME -> mapper.convertValue(payload, SessionWelcome.class);
            case SESSION_BYE -> mapper.convertValue(payload, SessionBye.class);
            case SESSION_PING -> mapper.convertValue(payload, SessionPing.class);
            case SESSION_PONG -> mapper.convertValue(payload, SessionPong.class);
            case SESSION_ACK -> mapper.convertValue(payload, SessionAck.class);
            case SESSION_LIST_JOBS -> mapper.convertValue(payload, SessionListJobs.class);
            case SESSION_JOBS -> mapper.convertValue(payload, SessionJobs.class);
            case JOB_SUBMIT -> mapper.convertValue(payload, JobSubmit.class);
            case JOB_ACCEPTED -> mapper.convertValue(payload, JobAccepted.class);
            case JOB_EVENT -> mapper.convertValue(payload, JobEvent.class);
            case JOB_RESULT -> mapper.convertValue(payload, JobResult.class);
            case JOB_ERROR -> mapper.convertValue(payload, JobError.class);
            case JOB_CANCEL -> mapper.convertValue(payload, JobCancel.class);
            case JOB_SUBSCRIBE -> mapper.convertValue(payload, JobSubscribe.class);
            case JOB_SUBSCRIBED -> mapper.convertValue(payload, JobSubscribed.class);
            case JOB_UNSUBSCRIBE -> mapper.convertValue(payload, JobUnsubscribe.class);
        };
    }

    public static ObjectNode encodePayload(ObjectMapper mapper, Message m) {
        JsonNode tree = mapper.valueToTree(m);
        if (!tree.isObject()) {
            throw new IllegalStateException("message payload must be an object: " + m);
        }
        return (ObjectNode) tree;
    }
}
