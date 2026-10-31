package com.apklama.plus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

/**
 * The Daybook Plus server: checks Bazaar subscriptions and passes subscribers' AI chats on.
 * Run behind an HTTPS reverse proxy (see README).
 */
public final class Main {
    private static final int VERIFY_MAX_BYTES = 8 * 1024;
    private static final int CHAT_MAX_BYTES = 1024 * 1024;

    /** A request body that is too big. */
    private static final class TooBig extends IOException {
    }

    private interface Handler {
        Reply handle(HttpExchange ex) throws Exception;
    }

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("bazaar-login")) {
            bazaarLogin(args);
            return;
        }
        Config c = Config.fromEnv(System.getenv());
        Store store = new Store(c.db());
        Plus plus = new Plus(store, new Bazaar(c.bazaarUrl(), c.bazaarClientId(), c.bazaarClientSecret(),
                c.bazaarRefreshToken()), c.packages(), c.products(), c.recheck(), c.dailyLimit());
        ChatProxy ai = new ChatProxy(c.aiUrl(), c.aiKey(), c.aiModel(), c.maxTokens());
        IpLimit verifyLimit = new IpLimit(c.verifyPerIp(), Duration.ofMinutes(10).toMillis());

        HttpServer server = HttpServer.create(new InetSocketAddress(c.port()), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        route(server, "/health", "GET", ex -> Reply.ok(new JSONObject().put("ok", true)));
        route(server, "/plus/verify", "POST", ex -> {
            long now = System.currentTimeMillis();
            if (!verifyLimit.allow(clientIp(ex, c.trustProxy()), now)) return Reply.error(429, "slow_down");
            return plus.verify(body(ex, VERIFY_MAX_BYTES), now);
        });
        route(server, "/v1/chat/completions", "POST", ex -> {
            Plus.Access a = plus.access(ex.getRequestHeaders().getFirst("Authorization"),
                    System.currentTimeMillis());
            if (a.refused() != null) return a.refused();
            return ai.forward(body(ex, CHAT_MAX_BYTES));
        });
        server.start();
        System.out.println("Daybook Plus server on port " + c.port() + ", AI model " + c.aiModel());
    }

    /** One exact path and method; everything else is 404/405. Logs one line per request, no secrets. */
    private static void route(HttpServer server, String path, String method, Handler h) {
        server.createContext(path, ex -> {
            long start = System.nanoTime();
            Reply r;
            try {
                if (!ex.getRequestURI().getPath().equals(path)) r = Reply.error(404, "not_found");
                else if (!ex.getRequestMethod().equals(method)) r = Reply.error(405, "method_not_allowed");
                else r = h.handle(ex);
            } catch (TooBig e) {
                r = Reply.error(413, "too_big");
            } catch (Exception e) {
                e.printStackTrace();
                r = Reply.error(500, "server_error");
            }
            send(ex, r);
            System.out.printf("%s %s %s %d %dms%n", Instant.now(), ex.getRequestMethod(),
                    ex.getRequestURI().getPath(), r.status(), (System.nanoTime() - start) / 1_000_000);
        });
    }

    private static String body(HttpExchange ex, int max) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) > 0; ) {
                if (buf.size() + n > max) throw new TooBig();
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange ex, Reply r) throws IOException {
        byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(r.status(), b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    /** Behind a proxy, the last X-Forwarded-For entry is the one our own proxy added. */
    private static String clientIp(HttpExchange ex, boolean trustProxy) {
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (trustProxy && fwd != null && !fwd.isBlank()) {
            String[] parts = fwd.split(",");
            return parts[parts.length - 1].trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    /** One-time setup: {@code bazaar-login <code> <redirect_uri>} prints the refresh token. */
    private static void bazaarLogin(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("Usage: java -jar plus-server.jar bazaar-login <code> <redirect_uri>");
            System.exit(2);
        }
        String id = System.getenv("BAZAAR_CLIENT_ID"), secret = System.getenv("BAZAAR_CLIENT_SECRET");
        if (id == null || secret == null) {
            System.err.println("Set BAZAAR_CLIENT_ID and BAZAAR_CLIENT_SECRET first.");
            System.exit(2);
        }
        System.out.println("BAZAAR_REFRESH_TOKEN=" + Bazaar.refreshTokenFor(id, secret, args[1], args[2]));
    }
}
