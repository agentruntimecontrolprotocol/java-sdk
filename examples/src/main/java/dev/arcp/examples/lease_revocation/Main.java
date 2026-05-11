package dev.arcp.examples.lease_revocation;

import dev.arcp.client.ARCPClient;
import dev.arcp.envelope.Envelope;
import dev.arcp.error.ARCPException;
import dev.arcp.error.ErrorCode;
import dev.arcp.examples.lease_revocation.Sql.Op;
import dev.arcp.examples.lease_revocation.Sql.StatementClass;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Warehouse DB admin agent. Reads pre-granted; writes prompt operator. */
public final class Main {

	private static final List<String> PRE_GRANTED = List.of("public.orders", "public.customers",
			"warehouse.fct_revenue_daily");
	private static final int READ_LEASE_SECONDS = 60 * 60;
	private static final int WRITE_LEASE_SECONDS = 5 * 60;

	record LeaseKey(String table, Op op) {
	}

	record Lease(String leaseId, Instant expiresAt) {
	}

	private Main() {
	}

	static Lease requestLease(ARCPClient client, String permission, String table, Op operation, int seconds,
			String reason) {
		// reply = client.request(client.envelope("permission.request", payload={
		// "permission": permission, "resource": "table:"+table,
		// "operation": operation, "reason": reason,
		// "requested_lease_seconds": seconds}), timeout=180s)
		// if reply.type == "permission.deny": throw PERMISSION_DENIED
		// return new Lease(reply.payload["lease_id"],
		// parse(reply.payload["expires_at"]))
		throw new UnsupportedOperationException("permission.request " + permission + " on table:" + table + " op="
				+ operation + " seconds=" + seconds + " reason=" + reason);
	}

	static Op authorize(ARCPClient client, String sql, Map<LeaseKey, Lease> leases) {
		StatementClass cls = Sql.classify(sql);
		if (cls.tables().isEmpty()) {
			throw new ARCPException(ErrorCode.INVALID_ARGUMENT, "no table referenced");
		}
		Op op = cls.op();
		int seconds = op == Op.READ ? READ_LEASE_SECONDS : WRITE_LEASE_SECONDS;
		for (String table : cls.tables()) {
			LeaseKey key = new LeaseKey(table, op);
			Lease cached = leases.get(key);
			if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
				continue;
			}
			leases.put(key, requestLease(client, "db." + op.name().toLowerCase(java.util.Locale.ROOT), table, op,
					seconds, op + " on " + table + ": " + sql.substring(0, Math.min(80, sql.length()))));
		}
		return op;
	}

	static void handleInbound(Envelope env, Map<LeaseKey, Lease> leases) {
		// Wire `lease.revoked` into the cache so the next call re-prompts.
		if (!"lease.revoked".equals(env.type())) {
			return;
		}
		// String lid = env.payload["lease_id"];
		// leases.entrySet().removeIf(e -> e.getValue().leaseId().equals(lid));
		throw new UnsupportedOperationException("evict revoked lease for " + env);
	}

	public static void main(String[] args) {
		ARCPClient client = null; // transport, identity, auth elided
		// client.open();

		Map<LeaseKey, Lease> leases = new HashMap<>();

		var drainer = Executors.newVirtualThreadPerTaskExecutor();
		drainer.submit(() -> {
			// for (Envelope env : client.events()) handleInbound(env, leases);
			throw new UnsupportedOperationException("drain inbound");
		});

		// Pre-grant the broad reads at session open. From here on, SELECT
		// against these tables runs free.
		for (String table : PRE_GRANTED) {
			leases.put(new LeaseKey(table, Op.READ),
					requestLease(client, "db.read", table, Op.READ, READ_LEASE_SECONDS, "bootstrap"));
		}

		// SELECT — covered by the bootstrap lease.
		authorize(client, "SELECT count(*) FROM public.orders WHERE shipped_at::date = current_date - 1", leases);
		// UPDATE — triggers permission.request; operator must approve.
		authorize(client, "UPDATE public.orders SET status='refunded' WHERE id=4812", leases);

		drainer.shutdownNow();
		// client.close();
	}
}
