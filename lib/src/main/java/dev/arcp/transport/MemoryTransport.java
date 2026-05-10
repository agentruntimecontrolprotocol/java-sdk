package dev.arcp.transport;

import dev.arcp.envelope.Envelope;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * In-memory bidirectional transport for tests and in-process scenarios. Each
 * {@code MemoryTransport} owns an inbound queue; its peer's
 * {@link #send(Envelope)} pushes into this queue.
 *
 * <p>
 * Preserves the §22 transport contract: envelope records survive the round-trip
 * unchanged; ordering within a {@code stream_id} is preserved by the underlying
 * {@code LinkedBlockingQueue}.
 */
public final class MemoryTransport implements Transport {

	private final LinkedBlockingQueue<Envelope> inbound = new LinkedBlockingQueue<>();
	private final AtomicBoolean closed = new AtomicBoolean(false);

	@Nullable
	private MemoryTransport peer;

	private MemoryTransport() {
	}

	/**
	 * @return a pair of memory transports {@code [a, b]} where {@code a.send(...)}
	 *         delivers to {@code b.receive(...)} and vice versa.
	 */
	public static MemoryTransport[] pair() {
		MemoryTransport a = new MemoryTransport();
		MemoryTransport b = new MemoryTransport();
		a.peer = b;
		b.peer = a;
		return new MemoryTransport[]{a, b};
	}

	@Override
	public void send(Envelope envelope) {
		Objects.requireNonNull(envelope, "envelope");
		if (closed.get()) {
			throw new IllegalStateException("transport closed");
		}
		MemoryTransport p = Objects.requireNonNull(peer, "peer not wired");
		p.inbound.add(envelope);
	}

	@Override
	public Optional<Envelope> receive(long timeout, TimeUnit unit) throws InterruptedException {
		return Optional.ofNullable(inbound.poll(timeout, unit));
	}

	@Override
	public boolean isClosed() {
		return closed.get();
	}

	@Override
	public void close() {
		closed.set(true);
	}
}
