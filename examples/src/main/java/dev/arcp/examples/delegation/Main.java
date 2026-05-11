package dev.arcp.examples.delegation;

import dev.arcp.client.ARCPClient;
import dev.arcp.envelope.Envelope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/** Fan a request out to peer runtimes; tolerate partial failure. */
public final class Main {

	private static final List<String> PEERS = List.of("research.web", "research.code", "research.docs");
	private static final Set<String> TERMINAL = Set.of("job.completed", "job.failed", "job.cancelled");

	private Main() {
	}

	public static final class Job {
		public final String target;
		public String jobId;
		public Map<String, Object> result;
		public Map<String, Object> error;

		public Job(String target) {
			this.target = target;
		}
	}

	static Job delegate(ARCPClient client, String target, String task, String traceId) {
		// accepted = client.request(client.envelope("agent.delegate",
		// trace_id=traceId,
		// payload={"target": target, "task": task,
		// "context": {"trace_id": traceId}}), timeout=10s)
		// if accepted.type != "job.accepted": return Job(target).withError(...)
		// return Job(target).withJobId(accepted.payload["job_id"])
		throw new UnsupportedOperationException("delegate to " + target + " trace=" + traceId);
	}

	/**
	 * Single reader on {@code client.events()}; fans out by {@code job_id}. Without
	 * this, parallel event loops starve each other.
	 */
	static final class JobMux {
		private final ARCPClient client;
		private final Map<String, LinkedBlockingQueue<Envelope>> queues = new HashMap<>();

		JobMux(ARCPClient client) {
			this.client = client;
		}

		void start() {
			Executors.newVirtualThreadPerTaskExecutor().submit(this::loop);
		}

		void register(String jobId) {
			queues.put(jobId, new LinkedBlockingQueue<>());
		}

		void loop() {
			// for (Envelope env : client.events()) {
			// String jid = env.jobId();
			// if (jid != null && queues.containsKey(jid)) {
			// queues.get(jid).put(env);
			// }
			// }
			throw new UnsupportedOperationException("mux loop on " + client);
		}

		Job collect(Job job) {
			if (job.error != null) {
				return job;
			}
			LinkedBlockingQueue<Envelope> q = queues.get(job.jobId);
			try {
				while (true) {
					Envelope env = q.take();
					switch (env.type()) {
						case "job.completed" -> job.result = Map.of(/* env.payload() */);
						case "job.failed" -> job.error = Map.of("code", "FAILED", "message", "...");
						case "job.cancelled" -> job.error = Map.of("code", "CANCELLED", "message", "cancelled");
						default -> {
							/* progress / log */ }
					}
					if (TERMINAL.contains(env.type())) {
						return job;
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
	}

	public static void main(String[] args) {
		ARCPClient client = null; // transport, identity, auth elided
		// client.open();

		JobMux mux = new JobMux(client);
		mux.start();

		String request = "what changed in our auth stack in the last 30 days?";
		String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

		List<Job> jobs = new ArrayList<>();
		for (String peer : PEERS) {
			Job j = delegate(client, peer, request, traceId);
			if (j.jobId != null) {
				mux.register(j.jobId);
			}
			jobs.add(j);
		}

		var execs = Executors.newVirtualThreadPerTaskExecutor();
		List<CompletableFuture<Job>> futures = new ArrayList<>();
		for (Job j : jobs) {
			futures.add(CompletableFuture.supplyAsync(() -> mux.collect(j), execs));
		}
		List<Job> completed = futures.stream().map(CompletableFuture::join).toList();
		System.out.println(Synth.synthesize(request, completed));

		execs.shutdownNow();
		// client.close();
	}
}
