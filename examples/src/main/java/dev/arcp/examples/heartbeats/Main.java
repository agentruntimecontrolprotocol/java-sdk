package dev.arcp.examples.heartbeats;

import dev.arcp.client.ARCPClient;
import dev.arcp.envelope.Envelope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Supervisor + worker pool. Heartbeat loss reroutes via idempotency_key. */
public final class Main {

	private static final int HEARTBEAT_INTERVAL_SECONDS = 15;
	private static final int DEADLINE_SECONDS = HEARTBEAT_INTERVAL_SECONDS * 2; // RFC §10.3 N=2

	private Main() {
	}

	static final class Worker {
		final String workerId;
		final String role;
		Instant lastHeartbeat;
		String inFlightJob;

		Worker(String workerId, String role, Instant lastHeartbeat) {
			this.workerId = workerId;
			this.role = role;
			this.lastHeartbeat = lastHeartbeat;
		}
	}

	record Task(String taskId, String role, Map<String, Object> payload, String idempotencyKey) {
	}

	static final class Roster {
		final Map<String, Worker> workers = new HashMap<>();
		final Map<String, List<String>> byRole = new HashMap<>();

		void add(Worker w) {
			workers.put(w.workerId, w);
			byRole.computeIfAbsent(w.role, k -> new ArrayList<>()).add(w.workerId);
		}

		List<Worker> candidates(String role) {
			List<Worker> out = new ArrayList<>();
			for (String wid : byRole.getOrDefault(role, List.of())) {
				Worker w = workers.get(wid);
				if (w != null && w.inFlightJob == null) {
					out.add(w);
				}
			}
			return out;
		}
	}

	// Supervisor side ------------------------------------------------------

	static void dispatch(ARCPClient client, Task task, Roster roster, Map<String, Task> jobsToTasks) {
		List<Worker> candidates = roster.candidates(task.role());
		if (candidates.isEmpty()) {
			throw new IllegalStateException("no idle workers for role=" + task.role());
		}
		Worker worker = candidates.stream().min((a, b) -> a.lastHeartbeat.compareTo(b.lastHeartbeat)).orElseThrow();
		// Same idempotency_key on every re-dispatch (RFC §6.4): a worker that
		// survived the network blip dedupes; it doesn't re-execute.
		// accepted = client.request(client.envelope("agent.delegate",
		// idempotency_key=task.idempotencyKey(),
		// payload={"target": worker.workerId, "task": task.taskId(),
		// "context": {"task_payload": task.payload()}}), 10s);
		// String jobId = accepted.payload["job_id"];
		// worker.inFlightJob = jobId; jobsToTasks.put(jobId, task);
		throw new UnsupportedOperationException("dispatch " + task.taskId() + " to " + worker.workerId);
	}

	static void supervise(ARCPClient client, Roster roster, Map<String, Task> jobsToTasks,
			ScheduledExecutorService sched) {
		sched.scheduleAtFixedRate(() -> {
			Instant now = Instant.now();
			for (Worker w : new ArrayList<>(roster.workers.values())) {
				if (Duration.between(w.lastHeartbeat, now).getSeconds() <= DEADLINE_SECONDS) {
					continue;
				}
				Task task = w.inFlightJob != null ? jobsToTasks.remove(w.inFlightJob) : null;
				if (task != null) {
					dispatch(client, task, roster, jobsToTasks);
				}
				roster.workers.remove(w.workerId);
				roster.byRole.getOrDefault(w.role, new ArrayList<>()).remove(w.workerId);
			}
		}, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

		// for (Envelope env : client.events()) {
		// if ("job.heartbeat".equals(env.type())) {
		// for (Worker w : roster.workers.values())
		// if (env.jobId() != null && env.jobId().toString().equals(w.inFlightJob))
		// w.lastHeartbeat = Instant.now();
		// } else if (TERMINAL.contains(env.type())) {
		// jobsToTasks.remove(env.jobId().toString());
		// clear inFlightJob on owner
		// }
		// }
	}

	// Worker side ----------------------------------------------------------

	static void heartbeatLoop(ARCPClient client, String jobId, AtomicBoolean stop) {
		int seq = 0;
		while (!stop.get()) {
			// client.send(client.envelope("job.heartbeat", job_id=jobId, payload={
			// "sequence": seq, "deadline_ms": HEARTBEAT_INTERVAL_SECONDS * 2000,
			// "state": "running"}));
			seq++;
			try {
				Thread.sleep(Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS).toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	static void execute(ARCPClient client, Envelope env) {
		String jobId = "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		// client.send(envelope("job.accepted", job_id=jobId, correlation_id=env.id(),
		// payload={"job_id": jobId, "state": "accepted"}));
		// client.send(envelope("job.started", job_id=jobId, payload={"job_id":
		// jobId}));
		AtomicBoolean stop = new AtomicBoolean(false);
		var execs = Executors.newVirtualThreadPerTaskExecutor();
		execs.submit(() -> heartbeatLoop(client, jobId, stop));
		try {
			// Map<String,Object> ctx = (Map) env.payload().get("context");
			// Object payload = ctx != null ? ctx.get("task_payload") : Map.of();
			Map<String, Object> result = Work.doWork(Map.of());
			// client.send(envelope("job.completed", job_id=jobId, payload={"result":
			// result}));
			if (result == null) {
				throw new UnsupportedOperationException("never");
			}
		} catch (RuntimeException ex) {
			// client.send(envelope("job.failed", job_id=jobId,
			// payload={"code": "INTERNAL", "message": ex.getMessage(), "retryable":
			// true}));
		} finally {
			stop.set(true);
			execs.shutdownNow();
		}
	}

	static void runWorker(ARCPClient client) {
		// for (Envelope env : client.events()) {
		// if ("agent.delegate".equals(env.type())) virtualThread(() -> execute(client,
		// env));
		// else if ("session.evicted".equals(env.type())) return;
		// }
		throw new UnsupportedOperationException("runWorker on " + client);
	}

	public static void main(String[] args) {
		ARCPClient supervisor = null; // transport, identity (privileged), auth elided
		// supervisor.open();
		Roster roster = new Roster();
		Map<String, Task> jobsToTasks = new HashMap<>();

		var execs = Executors.newVirtualThreadPerTaskExecutor();
		// Each worker is its own session in production; co-hosted here for the demo.
		for (String role : List.of("indexer", "extractor", "archiver")) {
			for (int i = 0; i < 2; i++) {
				ARCPClient w = null; // worker session, capabilities advertise role
				// w.open();
				final ARCPClient ww = w;
				execs.submit(() -> runWorker(ww));
				roster.add(new Worker(role + "-" + UUID.randomUUID().toString().substring(0, 6), role, Instant.now()));
			}
		}

		ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
		execs.submit(() -> supervise(supervisor, roster, jobsToTasks, sched));

		String[] roles = {"indexer", "extractor", "archiver"};
		for (int n = 0; n < 6; n++) {
			dispatch(supervisor, new Task(String.format("t%03d", n), roles[n % 3], Map.of("shard", n),
					"openclaw:t" + String.format("%03d", n)), roster, jobsToTasks);
		}

		try {
			Thread.sleep(60_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		sched.shutdownNow();
		execs.shutdownNow();
		// supervisor.close();
	}
}
