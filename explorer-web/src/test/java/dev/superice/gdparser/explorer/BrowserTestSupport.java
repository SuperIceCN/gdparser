package dev.superice.gdparser.explorer;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

final class BrowserTestSupport {
    private BrowserTestSupport() {
    }

    static Browser launch(Playwright playwright) {
        return launch(playwright, List.of());
    }

    static Browser launch(Playwright playwright, List<String> chromiumArgs) {
        var options = new BrowserType.LaunchOptions().setHeadless(true).setArgs(chromiumArgs);
        var executable = System.getenv("PLAYWRIGHT_CHROMIUM_EXECUTABLE");
        if (executable != null && !executable.isBlank()) {
            options.setExecutablePath(Path.of(executable));
        }
        return playwright.chromium().launch(options);
    }

    static Loopback start(Map<String, Response> routes) throws IOException {
        return new Loopback(Map.copyOf(routes));
    }

    record Response(int status, String contentType, byte[] bytes) {
        static Response file(Path path, String contentType) throws IOException {
            return new Response(200, contentType, Files.readAllBytes(path));
        }

        static Response text(String contentType, String text) {
            return new Response(200, contentType, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    record Request(String method, String path, int bodyLength) {
    }

    static final class Loopback implements AutoCloseable {
        private final HttpServer server;
        private final List<Request> requests = new CopyOnWriteArrayList<>();

        private Loopback(Map<String, Response> routes) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> respond(exchange, routes));
            server.start();
        }

        String url(String path) {
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("Expected an absolute request path");
            }
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        List<Request> requests() {
            return List.copyOf(requests);
        }

        private void respond(HttpExchange exchange, Map<String, Response> routes) throws IOException {
            try (exchange) {
                var target = exchange.getRequestURI();
                var method = exchange.getRequestMethod();
                requests.add(new Request(method, target.toString(), exchange.getRequestBody().readAllBytes().length));
                var response = method.equals("GET") ? routes.get(target.getPath()) : null;
                if (response == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", response.contentType());
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(response.status(), response.bytes().length == 0 ? -1 : response.bytes().length);
                exchange.getResponseBody().write(response.bytes());
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
