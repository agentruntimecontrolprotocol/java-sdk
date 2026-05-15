package dev.arcp.core.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * §4.2 stdio transport: newline-delimited JSON over a pair of byte streams.
 * Each envelope is written as one line (UTF-8, JSON, terminated by {@code \n}).
 *
 * <p>Use case: a parent process spawns a child agent and wires its
 * {@code process.getOutputStream()} → child stdin, child stdout →
 * {@code process.getInputStream()}. Both peers construct a {@link StdioTransport}
 * over their respective stream pair.
 *
 * <p>Framing rules:
 * <ul>
 *   <li>Outbound: JSON is written on one line (Jackson default, no embedded
 *       newlines) followed by {@code \n}.</li>
 *   <li>Inbound: a virtual-thread reader pulls one line at a time and parses
 *       it as an {@link Envelope}. Malformed lines are dropped with a WARN.</li>
 *   <li>End-of-stream terminates the inbound publisher.</li>
 *   <li>{@code stderr} is reserved for log output by convention; this
 *       transport never reads or writes it.</li>
 * </ul>
 */
public final class StdioTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);

    private final ObjectMapper mapper;
    private final BufferedWriter writer;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final BufferedReader reader;
    private final SubmissionPublisher<Envelope> inbound;
    private final Thread readerThread;
    private volatile boolean closed;

    public StdioTransport(InputStream in, OutputStream out) {
        this(in, out, ArcpMapper.shared());
    }

    public StdioTransport(InputStream in, OutputStream out, ObjectMapper mapper) {
        this.mapper = mapper != null ? mapper : ArcpMapper.shared();
        this.writer = new BufferedWriter(new OutputStreamWriter(
                Objects.requireNonNull(out, "out"), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(in, "in"), StandardCharsets.UTF_8));
        this.inbound = new SubmissionPublisher<>(
                Executors.newVirtualThreadPerTaskExecutor(), 1024);
        this.readerThread = Thread.ofVirtual().name("arcp-stdio-reader").unstarted(this::readLoop);
    }

    /** Begin reading lines from the input stream. */
    public StdioTransport start() {
        readerThread.start();
        return this;
    }

    private void readLoop() {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    inbound.submit(mapper.readValue(line, Envelope.class));
                } catch (IOException e) {
                    log.warn("malformed stdio envelope: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!closed) {
                inbound.closeExceptionally(e);
            }
        } finally {
            inbound.close();
        }
    }

    @Override
    public void send(Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (closed) {
            throw new IllegalStateException("transport closed");
        }
        // Lock held across blocking I/O to keep one envelope's bytes contiguous.
        writeLock.lock();
        try {
            writer.write(mapper.writeValueAsString(envelope));
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Flow.Publisher<Envelope> incoming() {
        return inbound;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            writer.close();
        } catch (IOException ignored) {
            // best-effort close
        }
        try {
            reader.close();
        } catch (IOException ignored) {
            // best-effort close
        }
        inbound.close();
        readerThread.interrupt();
    }
}
