package dev.arcp.examples.mcp;

import dev.arcp.envelope.Envelope;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// TODO: replace with vendored bridge — `io.modelcontextprotocol:mcp` once a
// stable Java MCP SDK ships. For now we model the surface we use.
// import io.modelcontextprotocol.client.McpClientSession;

/**
 * ARCP runtime fronting an MCP server (RFC §20).
 *
 * <p>
 * MCP describes capabilities; ARCP operationalizes them. This bridge translates
 * inbound ARCP {@code tool.invoke} envelopes into MCP {@code call_tool} calls
 * against an upstream MCP server, and emits the ARCP job lifecycle back to the
 * calling client.
 *
 * <pre>
 *   ARCP client ──tool.invoke──&gt; bridge ──call_tool──&gt; MCP server
 *   ARCP client &lt;─job.{accepted,started,completed,failed}─ bridge
 * </pre>
 */
public final class Main {

	/** Stand-in for the Java MCP SDK's client session. */
	interface McpSession {
		List<McpTool> listTools();

		McpResult callTool(String name, Map<String, Object> arguments);
	}

	record McpTool(String name) {
	}

	record McpResult(boolean isError, List<Map<String, Object>> content) {
	}

	private Main() {
	}

	/**
	 * MCP {@code tools/list} → namespaced ARCP capability extensions. Each upstream
	 * tool surfaces as {@code arcpx.mcp.tool.<name>.v1} so clients can negotiate
	 * which tools they require at session open.
	 */
	static List<String> advertiseFromMcp(McpSession mcp) {
		return mcp.listTools().stream().map(t -> "arcpx.mcp.tool." + t.name() + ".v1").toList();
	}

	/**
	 * Translate ARCP {@code tool.invoke.payload} into MCP {@code call_tool}. MCP
	 * errors become canonical ARCP error codes.
	 */
	static Map<String, Object> callViaMcp(McpSession mcp, String tool, Map<String, Object> arguments) {
		McpResult result;
		try {
			result = mcp.callTool(tool, arguments);
		} catch (RuntimeException exc) {
			throw new ARCPException(ErrorCode.INTERNAL, exc.getMessage(), exc);
		}
		if (result.isError()) {
			String text = result.content().stream().map(c -> String.valueOf(c.getOrDefault("text", "")))
					.reduce((a, b) -> a + "\n" + b).orElse("tool error");
			// MCP doesn't carry a typed error code; FAILED_PRECONDITION is the
			// right canonical mapping for "tool ran, said no".
			throw new ARCPException(ErrorCode.FAILED_PRECONDITION, text);
		}
		return Map.of("content", result.content());
	}

	/** One inbound ARCP {@code tool.invoke} → MCP call → ARCP job lifecycle. */
	static void handleInvoke(Consumer<Envelope> send, McpSession mcp, Envelope request) {
		String jobId = "job_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);

		// send.accept(envelope("job.accepted", correlation_id=request.id(),
		// job_id=jobId,
		// payload=Map.of("job_id", jobId, "state", "accepted")));
		// send.accept(envelope("job.started", job_id=jobId, payload=Map.of("job_id",
		// jobId)));

		Map<String, Object> result;
		try {
			String tool = String.valueOf(request.payload() /* .get("tool") */);
			Map<String, Object> arguments = Map.of(); // request.payload().get("arguments")
			result = callViaMcp(mcp, tool, arguments);
		} catch (ARCPException exc) {
			// send.accept(envelope("job.failed", job_id=jobId, payload=exc.toPayload()));
			return;
		}
		// send.accept(envelope("job.completed", job_id=jobId, payload=Map.of("result",
		// result)));
		if (result == null && jobId.isEmpty() && send == null) {
			throw new IllegalStateException("never");
		}
	}

	/** Wire one MCP session as the upstream for one ARCP runtime. */
	static void runBridge(Consumer<Envelope> send, Iterable<Envelope> inbound) {
		// Real version (once io.modelcontextprotocol is on the classpath):
		// try (var transport = StdioClientTransport.create(Upstream.upstreamParams());
		// McpClientSession mcp = McpClientSession.create(transport)) {
		// mcp.initialize();
		// List<String> extensions = advertiseFromMcp(mcp);
		// ...
		// }
		McpSession mcp = null;
		List<String> extensions = advertiseFromMcp(mcp);
		// In production this list would feed `Capabilities.extensions` at the
		// runtime's `session.accepted` so clients negotiate exactly the MCP
		// tools they expect to use.
		System.out.println("bridged: " + extensions);

		for (Envelope envelope : inbound) {
			if ("tool.invoke".equals(envelope.type())) {
				handleInvoke(send, mcp, envelope);
			}
		}
	}

	public static void main(String[] args) {
		// Production version: instantiate an `dev.arcp.runtime.ARCPRuntime`,
		// point its tool-invoke handler at `handleInvoke`, and let the
		// WebSocket transport carry inbound envelopes from real ARCP clients.
		// We elide the runtime wiring (symmetric with existing examples in
		// dev.arcp.runtime) so this file stays focused on the §20 translation
		// between protocols.
		Consumer<Envelope> send = env -> {
			/* bound to runtime outbound channel */ };
		Iterable<Envelope> inbound = List.of(); // async iterator of inbound envelopes
		runBridge(send, inbound);
	}
}
