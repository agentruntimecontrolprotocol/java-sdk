package dev.arcp.core.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.ids.MessageId;
import dev.arcp.core.transport.MemoryTransport;
import dev.arcp.core.transport.StdioTransport;
import dev.arcp.core.wire.Envelope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Branch coverage for transport edge cases: closed endpoints and stdio framing failures. */
class TransportEdgeCoverageTest {

  private static Envelope envelope(String type) {
    return Envelope.builder(type)
        .id(MessageId.of("m_edge"))
        .payload(JsonNodeFactory.instance.objectNode())
        .build();
  }

  @Test
  void memoryTransportRejectsSendAfterOwnClose() {
    MemoryTransport.Pair pair = MemoryTransport.pair();
    pair.runtime().close();
    assertThatThrownBy(() -> pair.runtime().send(envelope("session.ping")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transport closed");
    pair.client().close();
  }

  @Test
  void stdioTransportNullMapperFallsBackToShared() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    StdioTransport transport = new StdioTransport(new ByteArrayInputStream(new byte[0]), out, null);
    transport.send(envelope("session.ping"));
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"type\":\"session.ping\"");
    transport.close();
  }

  @Test
  void stdioTransportReadFailureClosesInboundExceptionally() throws Exception {
    InputStream failing =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("read boom");
          }
        };
    StdioTransport transport = new StdioTransport(failing, new ByteArrayOutputStream());
    CountDownLatch errored = new CountDownLatch(1);
    transport.incoming().subscribe(signalSubscriber(null, errored));
    transport.start();
    assertThat(errored.await(3, TimeUnit.SECONDS)).isTrue();
    transport.close();
  }

  @Test
  void stdioTransportWrapsWriteFailures() {
    OutputStream failing =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("write boom");
          }
        };
    StdioTransport transport = new StdioTransport(new ByteArrayInputStream(new byte[0]), failing);
    assertThatThrownBy(() -> transport.send(envelope("session.ping")))
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void stdioTransportCloseSwallowsStreamCloseFailures() {
    InputStream inThrowsOnClose =
        new InputStream() {
          @Override
          public int read() {
            return -1;
          }

          @Override
          public void close() throws IOException {
            throw new IOException("in close boom");
          }
        };
    OutputStream outThrowsOnClose =
        new OutputStream() {
          @Override
          public void write(int b) {}

          @Override
          public void close() throws IOException {
            throw new IOException("out close boom");
          }
        };
    StdioTransport transport = new StdioTransport(inThrowsOnClose, outThrowsOnClose);
    assertThatCode(transport::close).doesNotThrowAnyException();
  }

  /**
   * Drives the reader loop into its {@code closed}-flag exits: the loop condition re-check after an
   * empty line, and the IOException swallow once {@code close()} has begun. The input stream blocks
   * until the transport's writer (closed first in {@code close()}) signals that the closed flag is
   * already set, then either yields an empty line or throws.
   */
  @Test
  void stdioTransportReaderObservesCloseFlagDeterministically() throws Exception {
    for (boolean throwInsteadOfLine : new boolean[] {false, true}) {
      CountDownLatch readEntered = new CountDownLatch(1);
      CountDownLatch closeStarted = new CountDownLatch(1);
      InputStream blocking =
          new InputStream() {
            private boolean delivered;

            @Override
            public int read() throws IOException {
              readEntered.countDown();
              try {
                closeStarted.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
              }
              if (throwInsteadOfLine) {
                throw new IOException("stream torn down");
              }
              if (!delivered) {
                delivered = true;
                return '\n';
              }
              return -1;
            }
          };
      OutputStream signalingOut =
          new OutputStream() {
            @Override
            public void write(int b) {}

            @Override
            public void close() {
              // StdioTransport.close() closes the writer after setting the closed flag and
              // before closing the reader, so this signal proves closed == true.
              closeStarted.countDown();
            }
          };
      StdioTransport transport = new StdioTransport(blocking, signalingOut);
      transport.incoming().subscribe(signalSubscriber(null, null));
      transport.start();
      assertThat(readEntered.await(3, TimeUnit.SECONDS)).isTrue();
      Thread closer = new Thread(transport::close, "stdio-closer");
      closer.start();
      closer.join(TimeUnit.SECONDS.toMillis(5));
      assertThat(closer.isAlive()).isFalse();
      Thread readerThread = readerThreadOf(transport);
      readerThread.join(TimeUnit.SECONDS.toMillis(5));
      assertThat(readerThread.isAlive()).isFalse();
    }
  }

  private static Thread readerThreadOf(StdioTransport transport) throws Exception {
    java.lang.reflect.Field field = StdioTransport.class.getDeclaredField("readerThread");
    field.setAccessible(true);
    return (Thread) field.get(transport);
  }

  private static Flow.Subscriber<Envelope> signalSubscriber(
      CountDownLatch onComplete, CountDownLatch onError) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(Envelope item) {}

      @Override
      public void onError(Throwable throwable) {
        if (onError != null) {
          onError.countDown();
        }
      }

      @Override
      public void onComplete() {
        if (onComplete != null) {
          onComplete.countDown();
        }
      }
    };
  }
}
