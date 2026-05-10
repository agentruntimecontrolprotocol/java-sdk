package dev.arcp.util;

import com.github.f4b6a3.ulid.UlidCreator;

/** ULID generator. Monotonic within a JVM. */
public final class Ulid {

	private Ulid() {
	}

	/** @return a new monotonic ULID string. */
	public static String next() {
		return UlidCreator.getMonotonicUlid().toString();
	}
}
