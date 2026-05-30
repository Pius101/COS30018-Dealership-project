package negotiation.rest;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import negotiation.strategy.NegotiationStrategy;
import negotiation.strategy.StrategyRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP service used by helper scripts to discover registered strategies.
 */
public final class StrategyRestService {

    private static final Gson GSON = new Gson();
    private static HttpServer server;

    private StrategyRestService() {
    }

    public static synchronized void start(int port) throws IOException {
        if (server != null) {
            return;
        }

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/strategies", StrategyRestService::handleStrategies);
        server.createContext("/health", StrategyRestService::handleHealth);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("[StrategyRestService] Listening on http://localhost:" + port);
    }

    private static void handleStrategies(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        List<Map<String, String>> strategies = new ArrayList<>();
        for (String key : StrategyRegistry.getKeys()) {
            NegotiationStrategy strategy = StrategyRegistry.create(key);
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("key", key);
            entry.put("displayName", StrategyRegistry.getDisplayName(key));
            entry.put("description", strategy.getDescription());
            strategies.add(entry);
        }

        send(exchange, 200, strategies);
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        send(exchange, 200, Map.of("status", "ok"));
    }

    private static void send(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
