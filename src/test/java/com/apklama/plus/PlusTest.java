package com.apklama.plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

public class PlusTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = Duration.ofDays(1).toMillis();

    private Path dir;
    private Store store;
    /** What the fake Bazaar says: paid-until, null (not found), or -1 (Bazaar is down). */
    private Long bazaar;
    private Plus plus;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("plus");
        store = new Store(dir.resolve("test.db"));
        Purchases fake = (pkg, product, token) -> {
            if (bazaar != null && bazaar == -1) throw new IOException("down");
            return bazaar;
        };
        plus = new Plus(store, fake, Set.of("com.apklama.adhd"), Set.of("daybook_plus_monthly"),
                Duration.ofHours(24), 3);
    }

    @After
    public void tearDown() throws Exception {
        store.close();
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) Files.delete(p);
        }
        Files.delete(dir);
    }

    private static String request(String purchase) {
        return new JSONObject().put("packageName", "com.apklama.adhd").put("productId", "daybook_plus_monthly")
                .put("purchaseToken", purchase).toString();
    }

    private String paidToken(String purchase, long now) throws Exception {
        bazaar = now + 30 * DAY;
        Reply r = plus.verify(request(purchase), now);
        assertEquals(200, r.status());
        return new JSONObject(r.body()).getString("token");
    }

    @Test
    public void paidPurchaseGetsATokenThatWorks() throws Exception {
        bazaar = NOW + 30 * DAY;
        Reply r = plus.verify(request("p1"), NOW);
        assertEquals(200, r.status());
        JSONObject o = new JSONObject(r.body());
        assertEquals(NOW + 30 * DAY, o.getLong("until"));
        assertNull(plus.access("Bearer " + o.getString("token"), NOW).refused());
    }

    @Test
    public void notFoundOrEndedIsNotPaid() throws Exception {
        bazaar = null;
        assertEquals(402, plus.verify(request("p1"), NOW).status());
        bazaar = NOW - 1;
        assertEquals(402, plus.verify(request("p1"), NOW).status());
    }

    @Test
    public void bazaarDownIsTryLater() throws Exception {
        String token = paidToken("p1", NOW);
        bazaar = -1L;
        assertEquals(502, plus.verify(request("p1"), NOW).status());
        // Nothing changed: the old token still works
        assertNull(plus.access("Bearer " + token, NOW).refused());
    }

    @Test
    public void unknownProductAndOddTokensAreRefused() throws Exception {
        bazaar = NOW + DAY;
        String other = new JSONObject(request("p1")).put("productId", "something_else").toString();
        assertEquals(400, plus.verify(other, NOW).status());
        assertEquals(400, plus.verify(request("../../x"), NOW).status());
        assertEquals(400, plus.verify("not json", NOW).status());
    }

    @Test
    public void newVerifyReplacesTheOldToken() throws Exception {
        String first = paidToken("p1", NOW);
        String second = paidToken("p1", NOW + 1000);
        assertEquals(401, plus.access("Bearer " + first, NOW + 2000).refused().status());
        assertNull(plus.access("Bearer " + second, NOW + 2000).refused());
    }

    @Test
    public void notPaidAnyMoreStopsTheToken() throws Exception {
        String token = paidToken("p1", NOW);
        bazaar = null;
        plus.verify(request("p1"), NOW + 1000);
        assertEquals(401, plus.access("Bearer " + token, NOW + 2000).refused().status());
    }

    @Test
    public void tokenNeedsARecheckAfterADay() throws Exception {
        String token = paidToken("p1", NOW);
        assertNull(plus.access("Bearer " + token, NOW + DAY - 1).refused());
        assertEquals(401, plus.access("Bearer " + token, NOW + DAY).refused().status());
    }

    @Test
    public void tokenStopsWhenThePaidPeriodEnds() throws Exception {
        bazaar = NOW + 1000;
        String token = new JSONObject(plus.verify(request("p1"), NOW).body()).getString("token");
        assertEquals(401, plus.access("Bearer " + token, NOW + 1000).refused().status());
    }

    @Test
    public void badHeadersAreRefused() throws Exception {
        assertEquals(401, plus.access(null, NOW).refused().status());
        assertEquals(401, plus.access("Basic abc", NOW).refused().status());
        assertEquals(401, plus.access("Bearer unknown", NOW).refused().status());
    }

    @Test
    public void dailyLimitCountsPerDay() throws Exception {
        long morning = (NOW / DAY) * DAY + 1000;
        String token = paidToken("p1", morning);
        for (int i = 0; i < 3; i++) assertNull(plus.access("Bearer " + token, morning).refused());
        assertEquals(429, plus.access("Bearer " + token, morning).refused().status());
        // A new day starts a new count (token re-verified, as the app would after 24 hours)
        long tomorrow = morning + DAY;
        token = paidToken("p1", tomorrow);
        assertNotNull(plus.access("Bearer " + token, tomorrow));
        assertNull(plus.access("Bearer " + token, tomorrow).refused());
    }
}
