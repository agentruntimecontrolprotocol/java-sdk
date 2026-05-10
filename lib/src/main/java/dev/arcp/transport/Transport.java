package dev.arcp.transport;

import dev.arcp.envelope.Envelope;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Bidirectional envelope transport (RFC §22). Implementations preserve the
 * envelope body and the §6.4 delivery contract; ordering is guaranteed only
 * within {@code stream_id}/{@code job_id} per §6.5.
 */
public interface Transport extends AutoCloseable {

	/** Send one envelope. Blocks until the byte handoff is complete. */
	void send(Envelope envelope);

	/**
	 * Receive the next envelope, blocking up to {@code timeout}.
	 *
	 * @return the envelope, or {@link Optional#empty()} on timeout.
	 * @throws InterruptedException
	 *             if the calling thread was interrupted.
	 */
	Optional<Envelope> receive(long timeout, TimeUnit unit) throws InterruptedException;

	/** @return {@code true} once {@link #close()} has been called. */
	boolean isClosed();

	@Override
	void close();
}
