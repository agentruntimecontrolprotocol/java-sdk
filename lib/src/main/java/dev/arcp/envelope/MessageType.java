package dev.arcp.envelope;

import dev.arcp.messages.control.Ping;
import dev.arcp.messages.control.Pong;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sealed root of every ARCP payload variant (RFC §6.2).
 *
 * <p>
 * Concrete variants register themselves with {@link #register(String, Class)};
 * {@link Envelope} uses the registry during JSON deserialization to map the
 * envelope-level {@code type} discriminator to the right record class.
 * {@code switch} expressions over {@code MessageType} are compile-time
 * exhaustive — the registry exists only for the wire-format mapping, not for
 * dispatch.
 */
public sealed interface MessageType permits Ping, Pong {

	/** @return the wire-format discriminator (e.g. {@code "ping"}). */
	String type();

	/** Mutable mapping from wire type string to the concrete record class. */
	final class Registry {
		private static final Map<String, Class<? extends MessageType>> MAP = new ConcurrentHashMap<>();

		static {
			MAP.put("ping", Ping.class);
			MAP.put("pong", Pong.class);
		}

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
	 * Register a wire-format type string for a concrete variant. Idempotent. Used
	 * by additional message-type modules added in later phases.
	 */
	static void register(String type, Class<? extends MessageType> cls) {
		Registry.put(type, cls);
	}

	/** @return {@code true} if {@code type} is a recognized variant. */
	static boolean isKnown(String type) {
		return Registry.isKnown(type);
	}
}
