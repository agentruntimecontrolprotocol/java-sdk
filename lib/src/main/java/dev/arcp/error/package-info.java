/**
 * Canonical error taxonomy (RFC §18) and Java exception hierarchy.
 *
 * <p>
 * All public APIs throw {@link dev.arcp.error.ARCPException} or one of its
 * subclasses; downstream library exceptions are wrapped at the boundary.
 */
@org.jspecify.annotations.NullMarked
package dev.arcp.error;
