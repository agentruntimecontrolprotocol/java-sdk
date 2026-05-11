package dev.arcp.examples.mcp;

import java.util.List;

/**
 * Upstream MCP server invocation.
 *
 * <p>
 * Reference servers from the modelcontextprotocol org publish under
 * {@code mcp-server-*} (filesystem, git, postgres, slack, ...).
 */
public final class Upstream {

	public record StdioServerParameters(String command, List<String> args) {
	}

	private Upstream() {
	}

	public static StdioServerParameters upstreamParams() {
		return new StdioServerParameters("uvx", List.of("mcp-server-filesystem", "/srv/data"));
	}
}
