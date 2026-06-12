package dev.arcp.core.messages;

/**
 * Sealed type tag over every ARCP message kind. The wire {@code type} string is the canonical
 * discriminator; {@link Type} mirrors it as an enum for exhaustive switches in dispatch code.
 */
public sealed interface Message
    permits SessionHello,
        SessionWelcome,
        SessionBye,
        SessionClosed,
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
        JobCancelled,
        JobSubscribe,
        JobSubscribed,
        JobUnsubscribe {

  /**
   * Returns the {@link Type} discriminator matching this message's wire {@code type} string.
   *
   * @return the message type
   */
  Type kind();

  /** Wire {@code type} discriminator covering every protocol message (§3). */
  enum Type {
    /** §6.2 {@code session.hello}: client handshake with auth and capabilities. */
    SESSION_HELLO("session.hello"),

    /** §6.2 {@code session.welcome}: runtime handshake response. */
    SESSION_WELCOME("session.welcome"),

    // §6.7: the canonical graceful-close request is `session.close`; `session.bye` is accepted as a
    // deprecated alias on decode for one release (see #96).
    /**
     * §6.7 {@code session.close}: graceful close request ({@code session.bye} remains a deprecated
     * decode alias).
     */
    SESSION_BYE("session.close"),

    /** §6.7 {@code session.closed}: runtime acknowledgement of a graceful close. */
    SESSION_CLOSED("session.closed"),

    /** §6.4 {@code session.ping}: heartbeat probe from an idle peer. */
    SESSION_PING("session.ping"),

    /** §6.4 {@code session.pong}: prompt heartbeat reply. */
    SESSION_PONG("session.pong"),

    /** §6.5 {@code session.ack}: advisory highest-processed event sequence. */
    SESSION_ACK("session.ack"),

    /** §6.6 {@code session.list_jobs}: read-only job inventory request. */
    SESSION_LIST_JOBS("session.list_jobs"),

    /** §6.6 {@code session.jobs}: job inventory response. */
    SESSION_JOBS("session.jobs"),

    /** §7.1 {@code job.submit}: job submission request. */
    JOB_SUBMIT("job.submit"),

    /** §7.1 {@code job.accepted}: acceptance with effective lease and credentials. */
    JOB_ACCEPTED("job.accepted"),

    /** §8 {@code job.event}: kind-discriminated job event. */
    JOB_EVENT("job.event"),

    /** §7.3 {@code job.result}: terminal success payload (streamed per §8.4 when chunked). */
    JOB_RESULT("job.result"),

    /** §7.3 {@code job.error}: terminal failure payload with a §12 error code. */
    JOB_ERROR("job.error"),

    /** §7.4 {@code job.cancel}: cancellation request from the submitting session. */
    JOB_CANCEL("job.cancel"),

    /** §7.4 {@code job.cancelled}: cancellation acknowledgement. */
    JOB_CANCELLED("job.cancelled"),

    /** §7.6 {@code job.subscribe}: attach to a job's event stream. */
    JOB_SUBSCRIBE("job.subscribe"),

    /** §7.6 {@code job.subscribed}: subscription acknowledgement with a job snapshot. */
    JOB_SUBSCRIBED("job.subscribed"),

    /** §7.6 {@code job.unsubscribe}: detach from a job's event stream. */
    JOB_UNSUBSCRIBE("job.unsubscribe");

    private final String wire;

    Type(String wire) {
      this.wire = wire;
    }

    /**
     * Returns the canonical wire {@code type} string.
     *
     * @return the wire string
     */
    public String wire() {
      return wire;
    }

    private static final java.util.Map<String, Type> BY_WIRE;

    static {
      java.util.Map<String, Type> m = new java.util.HashMap<>();
      for (Type t : values()) {
        m.put(t.wire, t);
      }
      // §6.7 backward-compat alias: legacy `session.bye` decodes to the graceful-close type.
      m.put("session.bye", SESSION_BYE);
      BY_WIRE = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Resolves a wire {@code type} string, accepting the deprecated {@code session.bye} alias for
     * {@link #SESSION_BYE} (§6.7).
     *
     * @param wire the wire type string
     * @return the matching type
     * @throws IllegalArgumentException if the type is unknown
     */
    public static Type fromWire(String wire) {
      Type t = BY_WIRE.get(wire);
      if (t == null) {
        throw new IllegalArgumentException("unknown message type: " + wire);
      }
      return t;
    }
  }
}
