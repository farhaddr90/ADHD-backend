package com.apklama.plus;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cafe Bazaar's developer API (https://developers.cafebazaar.ir/en/guidelines/in-app-billing/api).
 * It uses OAuth: a long-lived refresh token (made once, see README) buys short access tokens.
 */
final class Bazaar implements Purchases {
    static final String BASE = "https://pardakht.cafebazaar.ir/devapi/v2";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String base, clientId, clientSecret, refreshToken;
    private String accessToken;
    private long accessUntil;

    Bazaar(String base, String clientId, String clientSecret, String refreshToken) {
        this.base = base;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
    }

    @Override
    public Long validUntil(String packageName, String productId, String purchaseToken) throws IOException {
        HttpResponse<String> r = get(packageName, productId, purchaseToken, false);
        // An access token can be revoked early; get a fresh one once
        if (r.statusCode() == 401) r = get(packageName, productId, purchaseToken, true);
        return parse(r.statusCode(), r.body());
    }

    /** Reads Bazaar's answer. Only "not_found" means "not bought"; any other error means "try later". */
    static Long parse(int status, String body) throws IOException {
        try {
            if (status == 200) return new JSONObject(body).getLong("validUntilTimestampMsec");
            if (status == 404 && "not_found".equals(new JSONObject(body).optString("error"))) return null;
        } catch (JSONException e) {
            throw new IOException("Bazaar sent a bad answer (HTTP " + status + ")", e);
        }
        throw new IOException("Bazaar answered HTTP " + status + ": " + shorten(body));
    }

    private HttpResponse<String> get(String pkg, String product, String purchase, boolean fresh)
            throws IOException {
        String url = base + "/api/applications/" + segment(pkg) + "/subscriptions/" + segment(product)
                + "/purchases/" + segment(purchase) + "/";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken(fresh)).GET().build();
        return send(req);
    }

    private synchronized String accessToken(boolean fresh) throws IOException {
        long now = System.currentTimeMillis();
        if (!fresh && accessToken != null && now < accessUntil) return accessToken;
        JSONObject o = token(Map.of("grant_type", "refresh_token", "client_id", clientId,
                "client_secret", clientSecret, "refresh_token", refreshToken));
        accessToken = o.getString("access_token");
        // Renew a minute early so a request never starts with a token about to die
        accessUntil = now + Math.max(0, o.optLong("expires_in", 3600) - 60) * 1000;
        return accessToken;
    }

    /** One-time setup: trades the code from Bazaar's "Authorize" page for a refresh token. */
    static String refreshTokenFor(String clientId, String clientSecret, String code, String redirectUri)
            throws IOException {
        return new Bazaar(BASE, clientId, clientSecret, "").token(Map.of("grant_type", "authorization_code",
                "code", code, "client_id", clientId, "client_secret", clientSecret,
                "redirect_uri", redirectUri)).getString("refresh_token");
    }

    private JSONObject token(Map<String, String> form) throws IOException {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/auth/token/")).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = send(req);
        if (r.statusCode() != 200) {
            throw new IOException("Bazaar login failed (HTTP " + r.statusCode() + "): " + shorten(r.body()));
        }
        try {
            return new JSONObject(r.body());
        } catch (JSONException e) {
            throw new IOException("Bazaar login sent a bad answer", e);
        }
    }

    private HttpResponse<String> send(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static String segment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String shorten(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
