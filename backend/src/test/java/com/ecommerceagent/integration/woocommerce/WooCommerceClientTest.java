package com.ecommerceagent.integration.woocommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * FIX-01: the response deadline covers headers and body. A store that sends headers and part of the body
 * and then goes silent must not block the caller: the client gives up at the deadline, closes the
 * connection and reports {@link WooClientException.Kind#TIMEOUT}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class WooCommerceClientTest {

    static final Duration DEADLINE = Duration.ofMillis(700);
    static final int MAX_BYTES = 1_000;
    static final WooCredentials CREDENTIALS = new WooCredentials("ck_test", "cs_test");

    final WooCommerceClient client = new WooCommerceClient(new StoreUrlPolicy(true, true, InetAddress::getAllByName),
            Duration.ofSeconds(2), DEADLINE, MAX_BYTES, Clock.systemUTC());

    RawStore store;

    @AfterEach
    void stop() throws IOException {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void readsACompleteResponseWithPaginationHeaders() throws Exception {
        store = RawStore.start(out -> {
            byte[] body = "[{\"id\":1}]".getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\nX-WP-Total: 1\r\n"
                    + "X-WP-TotalPages: 1\r\nContent-Length: " + body.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        });

        WooResponse response = client.get(store.url(), CREDENTIALS, "products", Map.of());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isJson()).isTrue();
        assertThat(response.body()).isEqualTo("[{\"id\":1}]");
        assertThat(response.total()).hasValue(1);
        assertThat(response.totalPages()).hasValue(1);
    }

    /** The audit scenario: headers, a JSON fragment, then silence on an open connection. */
    @Test
    void stalledBodyEndsAtTheDeadlineAndClosesTheConnection() throws Exception {
        store = RawStore.start(out -> {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 500\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write("[{\"id\":1,\"name\":\"Kosz".getBytes(StandardCharsets.UTF_8));
            out.flush();
            // Keep the connection open without sending anything more.
        });

        long started = System.nanoTime();
        WooClientException failure = catchThrowableOfType(WooClientException.class,
                () -> client.get(store.url(), CREDENTIALS, "products", Map.of()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(failure.kind()).isEqualTo(WooClientException.Kind.TIMEOUT);
        assertThat(elapsed).isGreaterThanOrEqualTo(DEADLINE).isLessThan(DEADLINE.plusSeconds(2));
        assertThat(store.awaitClientClosed(Duration.ofSeconds(5)))
                .as("the client closes the stalled connection").isTrue();
    }

    /** A store that keeps sending a byte now and then never trips a per-read timeout; the deadline still holds. */
    @Test
    void slowDripBodyEndsAtTheDeadline() throws Exception {
        store = RawStore.start(out -> {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 900\r\n\r\n[")
                    .getBytes(StandardCharsets.US_ASCII));
            for (int i = 0; i < 800; i++) {
                out.write(' ');
                out.flush();
                sleep(50);
            }
        });

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.get(store.url(), CREDENTIALS, "products", Map.of()))
                .isInstanceOfSatisfying(WooClientException.class,
                        e -> assertThat(e.kind()).isEqualTo(WooClientException.Kind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(DEADLINE.plusSeconds(2));
        assertThat(store.awaitClientClosed(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void headersThatNeverArriveEndAtTheDeadline() throws Exception {
        store = RawStore.start(out -> {
            // Accept the request and answer nothing.
        });

        assertThatThrownBy(() -> client.get(store.url(), CREDENTIALS, "products", Map.of()))
                .isInstanceOfSatisfying(WooClientException.class,
                        e -> assertThat(e.kind()).isEqualTo(WooClientException.Kind.TIMEOUT));
        assertThat(store.awaitClientClosed(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void declaredOversizedBodyIsRejectedWithoutReadingIt() throws Exception {
        store = RawStore.start(out -> {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + (MAX_BYTES + 1)
                    + "\r\n\r\n[").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        });

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.get(store.url(), CREDENTIALS, "products", Map.of()))
                .isInstanceOfSatisfying(WooClientException.class,
                        e -> assertThat(e.kind()).isEqualTo(WooClientException.Kind.RESPONSE_TOO_LARGE));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(DEADLINE);
        assertThat(store.awaitClientClosed(Duration.ofSeconds(5))).isTrue();
    }

    /** Chunked body without a length: the limit is enforced on the bytes actually received. */
    @Test
    void streamedOversizedBodyIsCutOffAtTheLimit() throws Exception {
        store = RawStore.start(out -> {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = " ".repeat(256).getBytes(StandardCharsets.US_ASCII);
            // Keep streaming until the client gives up; a write fails once it has closed the connection.
            for (int i = 0; i < 10_000; i++) {
                out.write(("100\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(chunk);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                sleep(1);
            }
        });

        assertThatThrownBy(() -> client.get(store.url(), CREDENTIALS, "products", Map.of()))
                .isInstanceOfSatisfying(WooClientException.class,
                        e -> assertThat(e.kind()).isEqualTo(WooClientException.Kind.RESPONSE_TOO_LARGE));
        assertThat(store.awaitClientClosed(Duration.ofSeconds(5))).isTrue();
    }

    /** An interrupted caller returns at once, keeps its interrupt flag and does not leave the exchange open. */
    @Test
    void interruptionCancelsTheExchange() throws Exception {
        WooCommerceClient patient = new WooCommerceClient(new StoreUrlPolicy(true, true, InetAddress::getAllByName),
                Duration.ofSeconds(2), Duration.ofSeconds(20), MAX_BYTES, Clock.systemUTC());
        store = RawStore.start(out -> {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n["
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
        });
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicReference<Boolean> interruptFlag = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                patient.get(store.url(), CREDENTIALS, "products", Map.of());
            } catch (Throwable e) {
                outcome.set(e);
            }
            interruptFlag.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        assertThat(store.awaitRequest(Duration.ofSeconds(5))).isTrue();
        caller.interrupt();
        caller.join(Duration.ofSeconds(3).toMillis());

        assertThat(caller.isAlive()).isFalse();
        assertThat(outcome.get()).isInstanceOf(WooClientException.class);
        assertThat(interruptFlag.get()).isTrue();
        assertThat(store.awaitClientClosed(Duration.ofSeconds(5))).isTrue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    interface Script {
        void respond(OutputStream out) throws IOException;
    }

    /**
     * A one-connection HTTP/1.1 server on a raw socket, so a test controls exactly which bytes are sent and
     * can observe when the client closes the connection.
     */
    static final class RawStore implements AutoCloseable {

        private final ServerSocket server;
        private final CountDownLatch requested = new CountDownLatch(1);
        private final CountDownLatch clientClosed = new CountDownLatch(1);
        private final Thread worker;

        private RawStore(Script script) throws IOException {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            worker = Thread.ofPlatform().daemon().start(() -> serve(script));
        }

        static RawStore start(Script script) throws IOException {
            return new RawStore(script);
        }

        URI url() {
            return URI.create("http://127.0.0.1:" + server.getLocalPort());
        }

        boolean awaitRequest(Duration timeout) throws InterruptedException {
            return requested.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        boolean awaitClientClosed(Duration timeout) throws InterruptedException {
            return clientClosed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void serve(Script script) {
            try (Socket socket = server.accept()) {
                InputStream in = socket.getInputStream();
                readRequestHead(in);
                requested.countDown();
                try {
                    script.respond(socket.getOutputStream());
                } catch (IOException clientGone) {
                    clientClosed.countDown();
                    return;
                }
                // The client has closed the connection when a read returns end of stream or fails.
                try {
                    while (in.read() != -1) {
                        // discard
                    }
                } catch (IOException reset) {
                    // connection reset by the client
                }
                clientClosed.countDown();
            } catch (IOException e) {
                // server closed by the test
            }
        }

        private static void readRequestHead(InputStream in) throws IOException {
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            while (matched < end.length) {
                int b = in.read();
                if (b == -1) {
                    throw new IOException("Request ended early");
                }
                matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            worker.interrupt();
        }
    }
}
