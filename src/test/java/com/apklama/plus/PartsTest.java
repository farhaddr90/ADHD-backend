package com.apklama.plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

public class PartsTest {
    @Test
    public void bazaarPaidAnswer() throws IOException {
        String body = "{\"kind\":\"androidpublisher#subscriptionPurchase\",\"initiationTimestampMsec\":1414181378566,"
                + "\"validUntilTimestampMsec\":1435912745710,\"autoRenewing\":true}";
        assertEquals(Long.valueOf(1435912745710L), Bazaar.parse(200, body));
    }

    @Test
    public void bazaarNotFoundIsNotBought() throws IOException {
        assertNull(Bazaar.parse(404, "{\"error\":\"not_found\",\"error_description\":\"not found\"}"));
    }

    @Test(expected = IOException.class)
    public void bazaarOtherErrorIsTryLater() throws IOException {
        Bazaar.parse(403, "{\"error\":\"access_denied\"}");
    }

    @Test(expected = IOException.class)
    public void bazaarHtmlPageIsTryLater() throws IOException {
        Bazaar.parse(404, "<html>gateway</html>");
    }

    @Test
    public void chatUsesTheServerModelAndCapsLength() {
        JSONObject r = ChatProxy.rewrite(new JSONObject("{\"model\":\"daybook\",\"stream\":true,\"messages\":[]}"),
                "real-model", 2048);
        assertEquals("real-model", r.getString("model"));
        assertFalse(r.has("stream"));
        assertEquals(2048, r.getInt("max_tokens"));
        assertEquals(500, ChatProxy.rewrite(new JSONObject("{\"max_tokens\":500}"), "m", 2048).getInt("max_tokens"));
        assertEquals(2048, ChatProxy.rewrite(new JSONObject("{\"max_tokens\":99999}"), "m", 2048).getInt("max_tokens"));
    }

    @Test
    public void ipLimitResetsAfterTheWindow() {
        IpLimit l = new IpLimit(2, 1000);
        assertTrue(l.allow("a", 0));
        assertTrue(l.allow("a", 10));
        assertFalse(l.allow("a", 20));
        assertTrue(l.allow("b", 20));
        assertTrue(l.allow("a", 1000));
    }

    @Test
    public void tokensAreRandomAndHashed() {
        String a = Tokens.newToken();
        assertFalse(a.equals(Tokens.newToken()));
        assertEquals(64, Tokens.hash(a).length());
        assertEquals(Tokens.hash(a), Tokens.hash(a));
    }
}
