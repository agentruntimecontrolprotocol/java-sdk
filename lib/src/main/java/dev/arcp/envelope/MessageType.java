package dev.arcp.envelope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Root of every ARCP payload variant (RFC §6.2). Concrete records implement
 * this interface and register themselves with {@link #register(String, Class)};
 * {@link Envelope} uses the registry during JSON deserialization to map the
 * envelope-level {@code type} discriminator to the right record class.
 *
 * <p>
 * {@code MessageType} is intentionally non-sealed so new variants can be added
 * by downstream modules without modifying this file. Compile-time
 * exhaustiveness is preserved on a per-handler basis via sealed sub-interfaces
 * (e.g. session messages, job messages).
 */
public interface MessageType {

	/** @return the wire-format discriminator (e.g. {@code "ping"}). */
	String type();

	/** Mutable mapping from wire type string to the concrete record class. */
	final class Registry {
		private static final Map<String, Class<? extends MessageType>> MAP = new ConcurrentHashMap<>();

		private Registry() {
		}

		static Class<? extends MessageType> resolve(String type) {
			Class<? extends MessageType> c = MAP.get(type);
			if (c == null) {
				throw new IllegalArgumentException("unknown message type: " + type);
			}
			return c;
		}

		static void put(String type, Class<? extends MessageType> cls) {
			MAP.put(type, cls);
		}

		static boolean isKnown(String type) {
			return MAP.containsKey(type);
		}
	}

	/**
	 * Register a wire-format type string for a concrete variant. Idempotent.
	 * Built-in message families call this in their class initializer.
	 */
	static void register(String type, Class<? extends MessageType> cls) {
		Registry.put(type, cls);
	}

	/** @return {@code true} if {@code type} is a recognized variant. */
	static boolean isKnown(String type) {
		return Registry.isKnown(type);
	}
}
