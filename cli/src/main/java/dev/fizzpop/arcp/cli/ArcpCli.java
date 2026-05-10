package dev.fizzpop.arcp.cli;

import dev.fizzpop.arcp.Version;

/**
 * Command-line entrypoint. Phase 0 stub; Phase 7 fills in
 * {@code arcp serve|tail|send|replay} via picocli.
 */
public final class ArcpCli {

	private ArcpCli() {
	}

	/**
	 * Entry point.
	 *
	 * @param args
	 *            command-line arguments
	 */
	public static void main(String[] args) {
		System.out.println(Version.IMPL_KIND + " " + Version.IMPL_VERSION);
	}
}
