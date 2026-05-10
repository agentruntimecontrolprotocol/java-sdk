package dev.arcp.extensions;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-runtime registry of advertised extension namespaces (RFC §21.2). A
 * namespace must be present here before the runtime will accept messages or
 * extension fields belonging to it; otherwise the dispatcher applies the §21.3
 * unknown-message rules.
 *
 * <p>
 * Thread-safe; multiple runtimes hold independent registries.
 */
public final class ExtensionRegistry {

	private final Set<String> namespaces = ConcurrentHashMap.newKeySet();

	/** Create an empty registry. */
	public ExtensionRegistry() {
	}

	/** Advertise an extension namespace. Validated against §21.1. */
	public void register(String ns) {
		ExtensionNamespace.require(ns);
		namespaces.add(ns);
	}

	/** Remove a previously-advertised namespace. */
	public void unregister(String ns) {
		namespaces.remove(ns);
	}

	/** @return {@code true} iff {@code ns} is currently advertised. */
	public boolean isAdvertised(String ns) {
		return namespaces.contains(ns);
	}

	/** @return an immutable snapshot of advertised namespaces. */
	public Set<String> snapshot() {
		return Set.copyOf(namespaces);
	}
}
