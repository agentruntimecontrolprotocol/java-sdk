package dev.arcp.examples.delegation;

import java.util.List;

/** Final-pass synthesizer. Real version: an Anthropic call. */
public final class Synth {

	private Synth() {
	}

	public static String synthesize(String request, List<Main.Job> jobs) {
		throw new UnsupportedOperationException("synthesize " + request + " over " + jobs.size() + " jobs");
	}
}
