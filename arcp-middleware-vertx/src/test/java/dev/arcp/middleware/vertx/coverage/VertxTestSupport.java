package dev.arcp.middleware.vertx.coverage;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.ServerWebSocket;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Fake {@link ServerWebSocket} plumbing for handler/transport tests without a Vert.x server. */
final class VertxTestSupport {

  private VertxTestSupport() {}

  /** Records every interaction the handler/transport performs against the socket. */
  static final class WsRecorder {
    volatile String path = "/arcp";
    volatile boolean failWrites;
    final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    final List<String> written = new CopyOnWriteArrayList<>();
    final List<String> closeReasons = new CopyOnWriteArrayList<>();
    final AtomicInteger closeCalls = new AtomicInteger();
    final AtomicReference<Handler<String>> textHandler = new AtomicReference<>();
    final AtomicReference<Handler<Void>> closeHandler = new AtomicReference<>();
    final AtomicReference<Handler<Throwable>> exceptionHandler = new AtomicReference<>();
  }

  @SuppressWarnings("unchecked")
  static ServerWebSocket serverWebSocket(WsRecorder rec) {
    return (ServerWebSocket)
        Proxy.newProxyInstance(
            VertxTestSupport.class.getClassLoader(),
            new Class<?>[] {ServerWebSocket.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "path":
                  return rec.path;
                case "headers":
                  return rec.headers;
                case "textMessageHandler":
                  rec.textHandler.set((Handler<String>) args[0]);
                  return proxy;
                case "closeHandler":
                  rec.closeHandler.set((Handler<Void>) args[0]);
                  return proxy;
                case "exceptionHandler":
                  rec.exceptionHandler.set((Handler<Throwable>) args[0]);
                  return proxy;
                case "writeTextMessage":
                  if (rec.failWrites) {
                    return Future.failedFuture(new IOException("write refused by fake"));
                  }
                  rec.written.add((String) args[0]);
                  return Future.succeededFuture();
                case "close":
                  rec.closeCalls.incrementAndGet();
                  if (args != null && args.length == 2) {
                    rec.closeReasons.add(args[0] + ":" + args[1]);
                  }
                  return Future.succeededFuture();
                case "toString":
                  return "FakeServerWebSocket(" + rec.path + ")";
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "equals":
                  return proxy == args[0];
                default:
                  Class<?> returnType = method.getReturnType();
                  if (returnType.isInstance(proxy)) {
                    return proxy;
                  }
                  if (returnType == Future.class) {
                    return Future.succeededFuture();
                  }
                  return defaultValue(returnType);
              }
            });
  }

  static String pingFrame() throws Exception {
    return ArcpMapper.shared().writeValueAsString(pingEnvelope());
  }

  static Envelope pingEnvelope() {
    return Envelope.builder("session.ping").payload(JsonNodeFactory.instance.objectNode()).build();
  }

  static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(10);
    }
  }

  /** Flow subscriber that records terminal signals for inbound-publisher assertions. */
  static final class CollectingSubscriber implements Flow.Subscriber<Envelope> {
    final List<Envelope> items = new CopyOnWriteArrayList<>();
    final CountDownLatch completed = new CountDownLatch(1);
    final CountDownLatch errored = new CountDownLatch(1);
    final AtomicReference<Throwable> error = new AtomicReference<>();

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(Envelope item) {
      items.add(item);
    }

    @Override
    public void onError(Throwable throwable) {
      error.set(throwable);
      errored.countDown();
    }

    @Override
    public void onComplete() {
      completed.countDown();
    }

    boolean awaitCompleted() throws InterruptedException {
      return completed.await(5, TimeUnit.SECONDS);
    }

    boolean awaitErrored() throws InterruptedException {
      return errored.await(5, TimeUnit.SECONDS);
    }
  }

  static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive() || type == void.class) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == double.class) {
      return 0d;
    }
    if (type == float.class) {
      return 0f;
    }
    if (type == char.class) {
      return (char) 0;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    return 0;
  }
}
