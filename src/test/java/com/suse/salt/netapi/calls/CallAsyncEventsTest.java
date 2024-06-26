package com.suse.salt.netapi.calls;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.suse.salt.netapi.client.SaltClient;
import com.suse.salt.netapi.client.impl.HttpAsyncClientImpl;
import com.suse.salt.netapi.datatypes.AuthMethod;
import com.suse.salt.netapi.datatypes.Batch;
import com.suse.salt.netapi.datatypes.Token;
import com.suse.salt.netapi.datatypes.target.Glob;
import com.suse.salt.netapi.errors.GenericError;
import com.suse.salt.netapi.errors.JsonParsingError;
import com.suse.salt.netapi.event.WebSocketEventStream;
import com.suse.salt.netapi.event.AbstractEventsTest;
import com.suse.salt.netapi.event.EventStream;
import com.suse.salt.netapi.exception.SaltException;
import com.suse.salt.netapi.results.Result;
import com.suse.salt.netapi.utils.ClientUtils;
import com.suse.salt.netapi.utils.TestUtils;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.websocket.DeploymentException;

/**
 * Tests for callAsync() taking an event stream to return results as they come in.
 */
public class CallAsyncEventsTest extends AbstractEventsTest {

    private static final int MOCK_HTTP_PORT = 8888;

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(MOCK_HTTP_PORT);

    static final AuthMethod AUTH = new AuthMethod(new Token());

    private SaltClient client;

    private CloseableHttpAsyncClient closeableHttpAsyncClient;

    @Override
    @Before
    public void init() throws URISyntaxException, DeploymentException {
        super.init();
        URI uri = URI.create("http://localhost:" + MOCK_HTTP_PORT);
        closeableHttpAsyncClient = TestUtils.defaultClient();
        client = new SaltClient(uri, new HttpAsyncClientImpl(closeableHttpAsyncClient));
    }

    @After
    public void cleanup() {
        super.cleanup();
        try {
            closeableHttpAsyncClient.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Class<?> config() {
        return CallAsyncEventsTestMessages.class;
    }

    public static <T> CompletableFuture<T> completeAfter(T value, Duration duration) {
        final CompletableFuture<T> promise = new CompletableFuture<>();
        SCHEDULER.schedule(
                () -> promise.complete(value), duration.toMillis(), MILLISECONDS
        );
        return promise;
    }

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(1);

    private String json(String name) {
        return ClientUtils.streamToString(CallAsyncEventsTest
                .class.getResourceAsStream(name));
    }

    @Test
    public void testAsyncCall() throws SaltException, InterruptedException {
        stubFor(post(urlMatching("/"))
                .withRequestBody(equalToJson(
                        json("/async_via_event_test_ping_request.json")
                ))

                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json("/async_via_event_test_ping_response.json"))));

        stubFor(post(urlMatching("/"))
                .withRequestBody(equalToJson(
                        json("/async_via_event_list_job_request.json")
                ))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json("/async_via_event_list_job_response.json"))));


        EventStream events = new WebSocketEventStream(uri, new Token("token"), 0, 0, 0);

        Map<String, CompletionStage<Result<Boolean>>> call =
                com.suse.salt.netapi.calls.modules.Test.ping().callAsync(
                    client,
                    Glob.ALL,
                    AUTH,
                    events,
                    completeAfter(
                            new GenericError("canceled"),
                            Duration.of(7, ChronoUnit.SECONDS)
                    ),
                    Optional.empty()
                ).toCompletableFuture().join().get();

        CountDownLatch countDownLatch = new CountDownLatch(5);
        Map<String, Result<Boolean>> results = new HashMap<>();
        Map<String, Long> times = new HashMap<>();
        call.forEach((key, value) -> {
            value.whenComplete((v, e) -> {
                if (v != null) {
                    results.put(key, v);
                }
                times.put(key, System.currentTimeMillis());
                countDownLatch.countDown();
            });
        });

        countDownLatch.await(10, TimeUnit.SECONDS);
        assertEquals(5, results.size());
        assertTrue(results.get("minion1").result().get());

        assertTrue(results.get("minion2").error().get() instanceof JsonParsingError);
        assertEquals("Expected BOOLEAN but was STRING at path $",
                ((JsonParsingError) results.get("minion2").error().get())
                        .getThrowable().getMessage());

        long delay12 = times.get("minion2") - times.get("minion1");
        assertTrue(delay12 >= 1000);

        assertTrue(results.get("minion3").result().get());

        long delay23 = times.get("minion3") - times.get("minion2");
        assertTrue(delay23 >= 1000);

        assertTrue(results.get("minion4").error().get() instanceof JsonParsingError);
        assertEquals("Expected BOOLEAN but was STRING at path $", ((JsonParsingError)
                results.get("minion4").error().get()).getThrowable().getMessage());

        long delay42 = times.get("minion4") - times.get("minion2");
        assertTrue(delay42 >= 1000);

        assertTrue(results.get("minion5").error().get() instanceof GenericError);
        assertEquals("canceled",
                ((GenericError) results.get("minion5").error().get()).getMessage());
    }

    @Test
    public void testAsyncBatchCall() throws SaltException, InterruptedException {
        stubFor(post(urlMatching("/"))
                .withRequestBody(equalToJson(
                        json("/async_batch_via_event_test_ping_request.json")
                ))

                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json("/async_via_event_test_ping_response.json"))));

        stubFor(post(urlMatching("/"))
                .withRequestBody(equalToJson(
                        json("/async_via_event_list_job_request.json")
                ))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json("/async_via_event_list_job_response.json"))));

        EventStream events = new WebSocketEventStream(uri, new Token("token"), 0, 0, 0);

        Map<String, CompletionStage<Result<Boolean>>> call =
                com.suse.salt.netapi.calls.modules.Test.ping().callAsync(
                    client,
                    Glob.ALL,
                    AUTH,
                    events,
                    completeAfter(
                            new GenericError("canceled"),
                            Duration.of(7, ChronoUnit.SECONDS)
                    ),
                    Optional.of(Batch.asAmount(1))
                ).toCompletableFuture().join().get();

        CountDownLatch countDownLatch = new CountDownLatch(5);
        Map<String, Result<Boolean>> results = new HashMap<>();
        Map<String, Long> times = new HashMap<>();
        call.forEach((key, value) -> {
            value.whenComplete((v, e) -> {
                if (v != null) {
                    results.put(key, v);
                }
                times.put(key, System.currentTimeMillis());
                countDownLatch.countDown();
            });
        });

        countDownLatch.await(10, TimeUnit.SECONDS);
        assertEquals(5, results.size());
        assertTrue(results.get("minion1").result().get());

        assertTrue(results.get("minion2").error().get() instanceof JsonParsingError);
        assertEquals("Expected BOOLEAN but was STRING at path $",
                ((JsonParsingError) results.get("minion2").error().get())
                        .getThrowable().getMessage());

        long delay12 = times.get("minion2") - times.get("minion1");
        assertTrue(delay12 >= 1000);

        assertTrue(results.get("minion3").result().get());

        long delay23 = times.get("minion3") - times.get("minion2");
        assertTrue(delay23 >= 1000);

        assertTrue(results.get("minion4").error().get() instanceof JsonParsingError);
        assertEquals("Expected BOOLEAN but was STRING at path $", ((JsonParsingError)
                results.get("minion4").error().get()).getThrowable().getMessage());

        long delay42 = times.get("minion4") - times.get("minion2");
        assertTrue(delay42 >= 1000);

        assertTrue(results.get("minion5").error().get() instanceof GenericError);
        assertEquals("canceled",
                ((GenericError) results.get("minion5").error().get()).getMessage());
    }
}
