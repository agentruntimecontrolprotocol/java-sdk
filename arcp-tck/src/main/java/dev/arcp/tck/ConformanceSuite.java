package dev.arcp.tck;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.arcp.client.ArcpClient;
import dev.arcp.client.JobHandle;
import dev.arcp.client.Session;
import dev.arcp.core.capabilities.Feature;
import dev.arcp.core.error.AgentVersionNotAvailableException;
import dev.arcp.core.error.DuplicateKeyException;
import dev.arcp.core.events.LogEvent;
import dev.arcp.core.messages.JobResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DynamicTest;

/**
 * Reusable conformance assertions parameterised by a {@link TckProvider}. The
 * downstream consumer wires this into a JUnit 5 {@code @TestFactory} like:
 *
 * <pre>{@code
 * @TestFactory
 * Stream<DynamicTest> conformance() {
 *     return ConformanceSuite.dynamicTests(this::buildProvider);
 * }
 * }</pre>
 */
public final class ConformanceSuite {

    private ConformanceSuite() {}

    /** Provider factory shape: each test invocation builds a fresh provider. */
    @FunctionalInterface
    public interface ProviderFactory {
        TckProvider create() throws Exception;
    }

    public static List<DynamicTest> dynamicTests(ProviderFactory factory) {
        return List.of(
                DynamicTest.dynamicTest(
                        "§5.1 envelope round-trip via session.welcome",
                        () -> runWith(factory, ConformanceSuite::handshakeReturnsWelcome)),
                DynamicTest.dynamicTest(
                        "§6.2 capability intersection with feature subset",
                        () -> runWith(factory, ConformanceSuite::featureIntersectionRespected)),
                DynamicTest.dynamicTest(
                        "§7.1 job.submit returns job.accepted with resolved agent",
                        () -> runWith(factory, ConformanceSuite::submitProducesAccepted)),
                DynamicTest.dynamicTest(
                        "§7.2 idempotency_key reuse returns the same job_id",
                        () -> runWith(factory, ConformanceSuite::idempotencyReuse)),
                DynamicTest.dynamicTest(
                        "§7.2 idempotency conflicting payload yields DUPLICATE_KEY",
                        () -> runWith(factory, ConformanceSuite::idempotencyConflict)),
                DynamicTest.dynamicTest(
                        "§7.5 agent@version unknown returns AGENT_VERSION_NOT_AVAILABLE",
                        () -> runWith(factory, ConformanceSuite::unknownAgentVersion)),
                DynamicTest.dynamicTest(
                        "§8.2 LogEvent reaches the client's events publisher",
                        () -> runWith(factory, ConformanceSuite::eventsReachSubscriber)));
    }

    private static void runWith(ProviderFactory factory, Assertion assertion) throws Exception {
        try (TckProvider provider = factory.create();
                ArcpClient client = provider.connect()) {
            assertion.run(provider, client);
        }
    }

    @FunctionalInterface
    private interface Assertion {
        void run(TckProvider provider, ArcpClient client) throws Exception;
    }

    // ------------------------------------------------------ assertions

    private static void handshakeReturnsWelcome(TckProvider p, ArcpClient client) throws Exception {
        Session session = client.connect(Duration.ofSeconds(5));
        assertThat(session.sessionId()).isNotNull();
    }

    private static void featureIntersectionRespected(TckProvider p, ArcpClient client)
            throws Exception {
        Session session = client.connect(Duration.ofSeconds(5));
        // Feature set is the intersection of what the client and runtime advertise.
        // We don't enforce membership of any specific feature; we only assert that
        // the negotiated set is non-null and contains no surprise features.
        for (Feature f : session.negotiatedFeatures()) {
            assertThat(Feature.values()).contains(f);
        }
    }

    private static void submitProducesAccepted(TckProvider p, ArcpClient client) throws Exception {
        client.connect(Duration.ofSeconds(5));
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("x", 1);
        JobHandle handle = client.submit(ArcpClient.jobSubmit("tck-echo@1.0.0", payload));
        assertThat(handle.jobId()).isNotNull();
        assertThat(handle.resolvedAgent()).startsWith("tck-echo@");
        JobResult result = handle.result().get(5, TimeUnit.SECONDS);
        assertThat(result.finalStatus()).isEqualTo(JobResult.SUCCESS);
    }

    private static void idempotencyReuse(TckProvider p, ArcpClient client) throws Exception {
        client.connect(Duration.ofSeconds(5));
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("x", 1);
        JobHandle first = client.submit(ArcpClient.jobSubmit(
                "tck-echo@1.0.0", payload, null, null, "tck-idem-1", null));
        JobHandle second = client.submit(ArcpClient.jobSubmit(
                "tck-echo@1.0.0", payload, null, null, "tck-idem-1", null));
        assertThat(second.jobId()).isEqualTo(first.jobId());
    }

    private static void idempotencyConflict(TckProvider p, ArcpClient client) throws Exception {
        client.connect(Duration.ofSeconds(5));
        ObjectNode first = JsonNodeFactory.instance.objectNode().put("x", 1);
        client.submit(ArcpClient.jobSubmit(
                "tck-echo@1.0.0", first, null, null, "tck-idem-2", null));
        ObjectNode second = JsonNodeFactory.instance.objectNode().put("x", 2);
        try {
            client.submit(ArcpClient.jobSubmit(
                    "tck-echo@1.0.0", second, null, null, "tck-idem-2", null));
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(DuplicateKeyException.class);
            return;
        }
        throw new AssertionError("expected DUPLICATE_KEY");
    }

    private static void unknownAgentVersion(TckProvider p, ArcpClient client) throws Exception {
        client.connect(Duration.ofSeconds(5));
        try {
            JobHandle handle = client.submit(ArcpClient.jobSubmit(
                    "tck-echo@9.99.99", JsonNodeFactory.instance.objectNode()));
            handle.result().get(2, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(AgentVersionNotAvailableException.class);
            return;
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(AgentVersionNotAvailableException.class);
            return;
        }
        throw new AssertionError("expected AGENT_VERSION_NOT_AVAILABLE");
    }

    private static void eventsReachSubscriber(TckProvider p, ArcpClient client) throws Exception {
        client.connect(Duration.ofSeconds(5));
        JobHandle handle = client.submit(ArcpClient.jobSubmit(
                "tck-log-emitter@1.0.0", JsonNodeFactory.instance.objectNode()));
        AtomicInteger logs = new AtomicInteger();
        handle.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(dev.arcp.core.events.EventBody body) {
                if (body instanceof LogEvent) {
                    logs.incrementAndGet();
                }
            }

            @Override
            public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {}
        });
        handle.result().get(5, TimeUnit.SECONDS);
        assertThat(logs.get()).isGreaterThanOrEqualTo(1);
    }
}
