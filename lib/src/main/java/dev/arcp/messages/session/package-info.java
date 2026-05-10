/**
 * Session-handshake messages (RFC §8). All nine variants required for the
 * four-step handshake plus mid-session refresh/eviction/close. Each record
 * implements {@link dev.arcp.envelope.MessageType}.
 */
@org.jspecify.annotations.NullMarked
package dev.arcp.messages.session;
