package com.apklama.plus;

import org.json.JSONObject;

/** An HTTP answer: status and JSON body. */
record Reply(int status, String body) {
    static Reply ok(JSONObject o) {
        return new Reply(200, o.toString());
    }

    /** {"error": code}, the same shape OpenAI and Bazaar use. */
    static Reply error(int status, String code) {
        return new Reply(status, new JSONObject().put("error", code).toString());
    }
}
