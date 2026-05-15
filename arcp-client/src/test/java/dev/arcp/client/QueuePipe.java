package dev.arcp.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Test-only thread-affinity-free pipe. {@link java.io.PipedInputStream}
 * remembers the first writing thread and fails reads if that thread dies —
 * a problem with virtual threads that submit one byte buffer then exit.
 * This queue-backed pipe has no such constraint.
 */
final class QueuePipe {

    private QueuePipe() {}

    static Pair pair() {
        BlockingQueue<Integer> aToB = new LinkedBlockingQueue<>();
        BlockingQueue<Integer> bToA = new LinkedBlockingQueue<>();
        Pair pair = new Pair();
        pair.aIn = stream(bToA);
        pair.aOut = sink(aToB);
        pair.bIn = stream(aToB);
        pair.bOut = sink(bToA);
        return pair;
    }

    static final class Pair {
        InputStream aIn;
        OutputStream aOut;
        InputStream bIn;
        OutputStream bOut;
    }

    private static final int EOF = -1;

    private static OutputStream sink(BlockingQueue<Integer> q) {
        return new OutputStream() {
            private volatile boolean closed;

            @Override
            public void write(int b) throws IOException {
                if (closed) {
                    throw new IOException("stream closed");
                }
                q.add(b & 0xFF);
            }

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    q.add(EOF);
                }
            }
        };
    }

    private static InputStream stream(BlockingQueue<Integer> q) {
        return new InputStream() {
            private volatile boolean closed;

            @Override
            public int read() throws IOException {
                if (closed) {
                    return EOF;
                }
                try {
                    Integer v = q.poll(30, TimeUnit.SECONDS);
                    if (v == null || v == EOF) {
                        closed = true;
                        return EOF;
                    }
                    return v;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }

            /**
             * The default {@link InputStream#read(byte[], int, int)} blocks on every
             * subsequent byte via {@link #read()}. With our 30-second poll that would
             * stall the test. Read the first byte blocking and then drain whatever the
             * queue holds non-blocking.
             */
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                int first = read();
                if (first == EOF) {
                    return EOF;
                }
                b[off] = (byte) first;
                int n = 1;
                while (n < len) {
                    Integer next = q.poll();
                    if (next == null) {
                        break;
                    }
                    if (next == EOF) {
                        closed = true;
                        // Push the sentinel back so future reads see EOF.
                        q.add(EOF);
                        break;
                    }
                    b[off + n] = next.byteValue();
                    n++;
                }
                return n;
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }
}
