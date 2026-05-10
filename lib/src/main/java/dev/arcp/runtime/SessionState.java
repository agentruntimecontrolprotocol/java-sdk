package dev.arcp.runtime;

/**
 * Session lifecycle state (RFC §8.1, §8.4, §8.5, §9). Until {@link #ACCEPTED},
 * all non-handshake messages MUST be dropped and logged by the dispatcher.
 */
public enum SessionState {
	OPENING, CHALLENGED, AUTHENTICATING, ACCEPTED, REFRESHING, CLOSING, EVICTED, REJECTED, CLOSED
}
