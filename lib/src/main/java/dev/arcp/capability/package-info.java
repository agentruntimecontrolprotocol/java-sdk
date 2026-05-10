/**
 * Capability negotiation primitives (RFC §7). Absent boolean capabilities are
 * treated as {@code false}; required-but-unsupported features yield
 * {@code session.rejected/UNIMPLEMENTED}.
 */
@org.jspecify.annotations.NullMarked
package dev.arcp.capability;
