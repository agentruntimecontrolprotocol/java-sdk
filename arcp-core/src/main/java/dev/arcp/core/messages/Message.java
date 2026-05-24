package dev.arcp.core.messages;

/**
 * Sealed type tag over every ARCP message kind. The wire {@code type} string is the canonical
 * discriminator; {@link Type} mirrors it as an enum for exhaustive switches in dispatch code.
 */
public sealed interface Message
    permits SessionHello,
        SessionWelcome,
        SessionBye,
        SessionPing,
        SessionPong,
        SessionAck,
        SessionListJobs,
        SessionJobs,
        JobSubmit,
        JobAccepted,
        JobEvent,
        JobResult,
        JobError,
        JobCancel,
        JobSubscribe,
        JobSubscribed,
        JobUnsubscribe {

  Type kind();

  enum Type {
    SESSION_HELLO("session.hello"),
    SESSION_WELCOME("session.welcome"),
    SESSION_BYE("session.bye"),
    SESSION_PING("session.ping"),
    SESSION_PONG("session.pong"),
    SESSION_ACK("session.ack"),
    SESSION_LIST_JOBS("session.list_jobs"),
    SESSION_JOBS("session.jobs"),
    JOB_SUBMIT("job.submit"),
    JOB_ACCEPTED("job.accepted"),
    JOB_EVENT("job.event"),
    JOB_RESULT("job.result"),
    JOB_ERROR("job.error"),
    JOB_CANCEL("job.cancel"),
    JOB_SUBSCRIBE("job.subscribe"),
    JOB_SUBSCRIBED("job.subscribed"),
    JOB_UNSUBSCRIBE("job.unsubscribe");

    private final String wire;

    Type(String wire) {
      this.wire = wire;
    }

    public String wire() {
      return wire;
    }

    private static final java.util.Map<String, Type> BY_WIRE;

    static {
      java.util.Map<String, Type> m = new java.util.HashMap<>();
      for (Type t : values()) {
        m.put(t.wire, t);
      }
      BY_WIRE = java.util.Collections.unmodifiableMap(m);
    }

    public static Type fromWire(String wire) {
      Type t = BY_WIRE.get(wire);
      if (t == null) {
        throw new IllegalArgumentException("unknown message type: " + wire);
      }
      return t;
    }
  }
}
