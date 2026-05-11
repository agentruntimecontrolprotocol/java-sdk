package dev.arcp.examples.resumability;

import dev.arcp.client.ARCPClient;
import java.util.Map;

/** Step bodies. Real version: a LangGraph-style node per step. */
public final class Steps {

	private Steps() {
	}

	public static Object runStep(ARCPClient client, String jobId, String step, Map<String, Object> inputs) {
		throw new UnsupportedOperationException("runStep " + step + " for " + jobId + " inputs=" + inputs);
	}
}
