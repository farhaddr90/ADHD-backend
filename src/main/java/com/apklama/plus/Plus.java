package com.apklama.plus;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The subscription rules, with no HTTP in them: checking a purchase (/plus/verify) and deciding
 * whether an access token may use the AI. Contract: android/docs/plus-server.md
 */
final class Plus {
    /** Bazaar purchase tokens are short and URL-safe; anything else is refused before calling Bazaar. */
    private static final Pattern PURCHASE = Pattern.compile("[A-Za-z0-9._~-]{1,512}");
    private static final long DAY_MS = Duration.ofDays(1).toMillis();

    /** Either the purchase that may use the AI, or why not. */
    record Access(String purchaseToken, Reply refused) {
    }

    private final Store store;
    private final Purchases purchases;
    private final Set<String> packages, products;
    private final long recheckMs;
    private final int dailyLimit;

    Plus(Store store, Purchases purchases, Set<String> packages, Set<String> products, Duration recheck,
         int dailyLimit) {
        this.store = store;
        this.purchases = purchases;
        this.packages = packages;
        this.products = products;
        this.recheckMs = recheck.toMillis();
        this.dailyLimit = dailyLimit;
    }

    /** 200 {token, until} when paid; 402 when not; 502 when Bazaar can't answer (the app keeps its state). */
    Reply verify(String body, long now) throws SQLException {
        String pkg, product, purchase;
        try {
            JSONObject o = new JSONObject(body);
            pkg = o.getString("packageName");
            product = o.getString("productId");
            purchase = o.getString("purchaseToken");
        } catch (JSONException e) {
            return Reply.error(400, "bad_request");
        }
        if (!packages.contains(pkg) || !products.contains(product)) return Reply.error(400, "unknown_product");
        if (!PURCHASE.matcher(purchase).matches()) return Reply.error(400, "bad_purchase_token");

        Long until;
        try {
            until = purchases.validUntil(pkg, product, purchase);
        } catch (IOException e) {
            System.err.println("Bazaar check failed: " + e.getMessage());
            return Reply.error(502, "store_unavailable");
        }
        // Bazaar also answers for ended subscriptions, so the date decides
        if (until == null || until <= now) {
            store.end(purchase);
            return Reply.error(402, "not_paid");
        }
        String token = Tokens.newToken();
        store.grant(purchase, pkg, product, until, now, Tokens.hash(token));
        return Reply.ok(new JSONObject().put("token", token).put("until", until));
    }

    /**
     * May this "Authorization" header use the AI now? A token works until the paid period ends, and
     * at most {@code recheck} after Bazaar last said "paid" (so refunds are noticed). After that the
     * app gets 401, verifies again on its own, and retries.
     */
    Access access(String authorization, long now) throws SQLException {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return new Access(null, Reply.error(401, "no_token"));
        }
        Store.Session s = store.find(Tokens.hash(authorization.substring(7).trim()));
        if (s == null || now >= s.validUntil() || now - s.checkedAt() >= recheckMs) {
            return new Access(null, Reply.error(401, "token_expired"));
        }
        if (!store.use(s.purchaseToken(), now / DAY_MS, dailyLimit)) {
            return new Access(null, Reply.error(429, "daily_limit"));
        }
        return new Access(s.purchaseToken(), null);
    }
}
