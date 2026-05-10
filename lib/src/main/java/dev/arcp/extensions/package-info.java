/**
 * Extension namespace validation and registry (RFC §21).
 *
 * <p>
 * Extensions MUST be namespaced as {@code arcpx.<vendor>.<name>.v<n>} or
 * reverse-DNS (§21.1) and MUST be advertised in {@code capabilities.extensions}
 * (§21.2). Unknown messages are nacked with
 * {@link dev.arcp.error.ErrorCode#UNIMPLEMENTED} unless
 * {@code extensions.optional} is true (§21.3).
 */
@org.jspecify.annotations.NullMarked
package dev.arcp.extensions;
