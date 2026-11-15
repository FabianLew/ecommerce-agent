package com.ecommerceagent.integration.meta;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for the Graph API contract the adapter relies on (ADR-0017). Routes are matched on the path after
 * the version; every request is recorded with its URI and Authorization header.
 */
public final class GraphApiStub implements AutoCloseable {

    public static final String TOKEN = "EAAstubtoken000000000000000000wxyz";

    public record Reply(int status, String body, Map<String, String> headers, long delayMillis) {

        public static Reply ok(String body) {
            return new Reply(200, body, Map.of(), 0);
        }

        public static Reply error(int status, int code, int subcode) {
            return new Reply(status, "{\"error\":{\"message\":\"stub\",\"code\":" + code + ",\"error_subcode\":"
                    + subcode + "}}", Map.of(), 0);
        }

        public Reply withHeader(String name, String value) {
            Map<String, String> copy = new java.util.HashMap<>(headers);
            copy.put(name, value);
            return new Reply(status, body, copy, delayMillis);
        }

        public Reply delayed(long millis) {
            return new Reply(status, body, headers, millis);
        }
    }

    public record Recorded(String method, URI uri, String authorization, String body) {
    }

    private final HttpServer server;
    private final Map<String, Function<HttpExchange, Reply>> routes = new ConcurrentHashMap<>();
    public final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public GraphApiStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Replies on {@code METHOD path} (path after /v23.0/, without query); the function sees the exchange. */
    public GraphApiStub on(String methodAndPath, Function<HttpExchange, Reply> reply) {
        routes.put(methodAndPath, reply);
        return this;
    }

    public GraphApiStub on(String methodAndPath, Reply reply) {
        return on(methodAndPath, exchange -> reply);
    }

    public void reset() {
        routes.clear();
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI(),
                exchange.getRequestHeaders().getFirst("Authorization"), body));
        String path = exchange.getRequestURI().getPath().replaceFirst("^/v[0-9.]+/", "");
        Reply reply;
        if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            reply = Reply.error(400, 190, 0);
        } else {
            Function<HttpExchange, Reply> route = routes.get(exchange.getRequestMethod() + " " + path);
            reply = route == null ? Reply.error(400, 100, 33) : route.apply(exchange);
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        try {
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException e) {
            // The client gave up (timeout test).
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
