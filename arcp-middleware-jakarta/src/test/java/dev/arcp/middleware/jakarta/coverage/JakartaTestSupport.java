package dev.arcp.middleware.jakarta.coverage;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.arcp.core.wire.ArcpMapper;
import dev.arcp.core.wire.Envelope;
import jakarta.websocket.CloseReason;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.server.HandshakeRequest;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Shared fakes for exercising the Jakarta adapter without a running container. */
final class JakartaTestSupport {

  private JakartaTestSupport() {}

  /** Records the interactions the adapter/endpoint perform against a session. */
  static final class SessionRecorder {
    final List<CloseReason> closeReasons = new CopyOnWriteArrayList<>();
    final List<String> sentText = new CopyOnWriteArrayList<>();
    final AtomicReference<MessageHandler.Whole<String>> textHandler = new AtomicReference<>();
    volatile boolean plainCloseCalled;
  }

  static Session session(String id, SessionRecorder rec) {
    return session(id, rec, false, false);
  }

  @SuppressWarnings("unchecked")
  static Session session(
      String id, SessionRecorder rec, boolean throwOnClose, boolean throwOnSend) {
    RemoteEndpoint.Basic basic =
        (RemoteEndpoint.Basic)
            Proxy.newProxyInstance(
                JakartaTestSupport.class.getClassLoader(),
                new Class<?>[] {RemoteEndpoint.Basic.class},
                (proxy, method, args) -> {
                  if ("sendText".equals(method.getName()) && args != null && args.length == 1) {
                    if (throwOnSend) {
                      throw new IOException("send refused by fake");
                    }
                    rec.sentText.add((String) args[0]);
                    return null;
                  }
                  return defaultValue(method.getReturnType());
                });
    return (Session)
        Proxy.newProxyInstance(
            JakartaTestSupport.class.getClassLoader(),
            new Class<?>[] {Session.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getId" -> id;
                  case "close" -> {
                    if (throwOnClose) {
                      throw new IOException("close refused by fake");
                    }
                    if (args != null && args.length == 1) {
                      rec.closeReasons.add((CloseReason) args[0]);
                    } else {
                      rec.plainCloseCalled = true;
                    }
                    yield null;
                  }
                  case "addMessageHandler" -> {
                    if (args != null
                        && args.length == 2
                        && args[1] instanceof MessageHandler.Whole<?> whole) {
                      rec.textHandler.set((MessageHandler.Whole<String>) whole);
                    }
                    yield null;
                  }
                  case "getBasicRemote" -> basic;
                  case "isOpen" -> true;
                  case "toString" -> "FakeSession(" + id + ")";
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default -> defaultValue(method.getReturnType());
                });
  }

  static HandshakeRequest handshakeRequest(Map<String, List<String>> headers) {
    return new HandshakeRequest() {
      @Override
      public Map<String, List<String>> getHeaders() {
        return headers;
      }

      @Override
      public Principal getUserPrincipal() {
        return null;
      }

      @Override
      public URI getRequestURI() {
        return URI.create("/arcp");
      }

      @Override
      public boolean isUserInRole(String role) {
        return false;
      }

      @Override
      public Object getHttpSession() {
        return null;
      }

      @Override
      public Map<String, List<String>> getParameterMap() {
        return Map.of();
      }

      @Override
      public String getQueryString() {
        return "";
      }
    };
  }

  static HandshakeResponse handshakeResponse() {
    Map<String, List<String>> headers = new HashMap<>();
    return () -> headers;
  }

  static String pingFrame() throws Exception {
    Envelope envelope =
        Envelope.builder("session.ping").payload(JsonNodeFactory.instance.objectNode()).build();
    return ArcpMapper.shared().writeValueAsString(envelope);
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
