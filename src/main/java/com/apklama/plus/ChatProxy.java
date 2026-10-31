package com.apklama.plus;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Passes the app's OpenAI-format chat to the real AI provider with the server's own key and model.
 * The provider's own auth errors become 502: a 401 would make the app re-verify for nothing.
 */
final class ChatProxy {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String url, key, model;
    private final int maxTokens;

    ChatProxy(String baseUrl, String key, String model, int maxTokens) {
        this.url = baseUrl + "/chat/completions";
        this.key = key;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    Reply forward(String body) {
        JSONObject req;
        try {
            req = rewrite(new JSONObject(body), model, maxTokens);
        } catch (JSONException e) {
            return Reply.error(400, "bad_request");
        }
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(req.toString())).build();
        HttpResponse<String> res;
        try {
            res = http.send(r, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            System.err.println("AI provider unreachable: " + e.getMessage());
            return Reply.error(502, "ai_unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error(502, "ai_unavailable");
        }
        int code = res.statusCode();
        if (code == 200 || code == 400 || code == 429) return new Reply(code, res.body());
        String text = res.body();
        System.err.println("AI provider answered HTTP " + code + ": "
                + (text.length() > 300 ? text.substring(0, 300) : text));
        return Reply.error(502, "ai_unavailable");
    }

    /** The server picks the model and caps the answer length; streaming isn't supported. */
    static JSONObject rewrite(JSONObject req, String model, int maxTokens) {
        req.put("model", model);
        req.remove("stream");
        if (maxTokens > 0) req.put("max_tokens", Math.min(req.optInt("max_tokens", maxTokens), maxTokens));
        return req;
    }
}
