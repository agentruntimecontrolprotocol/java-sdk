package dev.arcp.middleware.spring.coverage;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Shared fakes and wire helpers for the Spring adapter coverage tests. */
final class SpringTestSupport {

  private SpringTestSupport() {}

  /** Records the interactions the handler/transport perform against a session. */
  static final class WebSessionRecorder {
    final List<WebSocketMessage<?>> sent = new CopyOnWriteArrayList<>();
    volatile boolean closed;
    volatile boolean throwOnSend;
    volatile boolean throwOnClose;
  }

  static WebSocketSession webSocketSession(String id, WebSessionRecorder rec) {
    return (WebSocketSession)
        Proxy.newProxyInstance(
            SpringTestSupport.class.getClassLoader(),
            new Class<?>[] {WebSocketSession.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getId" -> id;
                  case "sendMessage" -> {
                    if (rec.throwOnSend) {
                      throw new IOException("send refused by fake");
                    }
                    rec.sent.add((WebSocketMessage<?>) args[0]);
                    yield null;
                  }
                  case "close" -> {
                    if (rec.throwOnClose) {
                      throw new IOException("close refused by fake");
                    }
                    rec.closed = true;
                    yield null;
                  }
                  case "isOpen" -> true;
                  case "toString" -> "FakeWebSocketSession(" + id + ")";
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default -> defaultValue(method.getReturnType());
                });
  }

  /** Records the handler registration performed by the auto-configuration. */
  static final class RegistryRecorder {
    volatile WebSocketHandler handler;
    volatile String[] paths;
    volatile String[] origins;
    final List<HandshakeInterceptor> interceptors = new CopyOnWriteArrayList<>();
  }

  static WebSocketHandlerRegistry registry(RegistryRecorder rec) {
    WebSocketHandlerRegistration registration =
        (WebSocketHandlerRegistration)
            Proxy.newProxyInstance(
                SpringTestSupport.class.getClassLoader(),
                new Class<?>[] {WebSocketHandlerRegistration.class},
                (proxy, method, args) -> {
                  switch (method.getName()) {
                    case "setAllowedOrigins":
                      rec.origins = (String[]) args[0];
                      return proxy;
                    case "addInterceptors":
                      rec.interceptors.addAll(Arrays.asList((HandshakeInterceptor[]) args[0]));
                      return proxy;
                    default:
                      return method.getReturnType().isInstance(proxy)
                          ? proxy
                          : defaultValue(method.getReturnType());
                  }
                });
    return (WebSocketHandlerRegistry)
        Proxy.newProxyInstance(
            SpringTestSupport.class.getClassLoader(),
            new Class<?>[] {WebSocketHandlerRegistry.class},
            (proxy, method, args) -> {
              if ("addHandler".equals(method.getName())) {
                rec.handler = (WebSocketHandler) args[0];
                rec.paths = (String[]) args[1];
                return registration;
              }
              return defaultValue(method.getReturnType());
            });
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
}
