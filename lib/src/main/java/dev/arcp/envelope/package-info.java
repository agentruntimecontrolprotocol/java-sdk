/**
 * Wire-format envelope (RFC §6.1) and message-type discriminator.
 *
 * <p>
 * {@link dev.arcp.envelope.Envelope} carries every §6.1 field;
 * {@link dev.arcp.envelope.MessageType} is the sealed root of the payload
 * hierarchy. Jackson polymorphism is configured directly on the sealed
 * interface via {@code @JsonTypeInfo} + {@code @JsonSubTypes}, giving
 * compile-time exhaustive {@code switch} dispatch.
 */
@org.jspecify.annotations.NullMarked
package dev.arcp.envelope;
