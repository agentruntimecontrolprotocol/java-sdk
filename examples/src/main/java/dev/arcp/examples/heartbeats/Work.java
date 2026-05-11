package dev.arcp.examples.heartbeats;

import java.util.Map;

/**
 * Worker work. Real version: a CrewAI-style Crew via Java equivalent (e.g.
 * LangChain4j).
 */
public final class Work {

	private Work() {
	}

	public static Map<String, Object> doWork(Map<String, Object> payload) {
		throw new UnsupportedOperationException("doWork: " + payload);
	}
}
