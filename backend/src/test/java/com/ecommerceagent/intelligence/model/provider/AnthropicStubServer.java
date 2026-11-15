package com.ecommerceagent.intelligence.model.provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * Minimal local stand-in for the Anthropic Messages API (POST /v1/messages).
 * Returns queued JSON responses in order and records every request body.
 * Uses only the JDK. Each queued response has a status; 500 with an API error when nothing is queued.
 */
final class AnthropicStubServer implements AutoCloseable {

    private final HttpServer server;
    private final Deque<String[]> responses = new ArrayDeque<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    AnthropicStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                requests.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            String[] queued;
            synchronized (responses) {
                queued = responses.isEmpty() ? null : responses.poll();
            }
            String body = queued == null ? null : queued[1];
            int status = queued == null ? 500 : Integer.parseInt(queued[0]);
            byte[] bytes = (body == null
                    ? "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"no stubbed response\"}}"
                    : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (status == 429) {
                exchange.getResponseHeaders().add("retry-after", "7");
            }
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void enqueue(String json) {
        enqueue(200, json);
    }

    void enqueue(int status, String json) {
        synchronized (responses) {
            responses.add(new String[] {Integer.toString(status), json});
        }
    }

    static String error(String type) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"stub\"}}";
    }

    static String truncated(String text) {
        return """
                {"id":"msg_cut","type":"message","role":"assistant","model":"claude-stub",
                 "content":[{"type":"text","text":"%s"}],
                 "stop_reason":"max_tokens","stop_sequence":null,
                 "usage":{"input_tokens":300,"output_tokens":50}}
                """.formatted(text);
    }

    List<String> requests() {
        return requests;
    }

    void reset() {
        synchronized (responses) {
            responses.clear();
        }
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static String toolUse(String toolUseId, String toolName, String inputJson) {
        return """
                {"id":"msg_tool","type":"message","role":"assistant","model":"claude-stub",
                 "content":[{"type":"tool_use","id":"%s","name":"%s","input":%s}],
                 "stop_reason":"tool_use","stop_sequence":null,
                 "usage":{"input_tokens":120,"output_tokens":30}}
                """.formatted(toolUseId, toolName, inputJson);
    }

    static String text(String text) {
        return """
                {"id":"msg_text","type":"message","role":"assistant","model":"claude-stub",
                 "content":[{"type":"text","text":"%s"}],
                 "stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":200,"output_tokens":40}}
                """.formatted(text);
    }
}
