package com.apklama.plus;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Server settings, all from environment variables (see .env.example). */
record Config(
        int port,
        Path db,
        String bazaarUrl,
        Set<String> packages,
        Set<String> products,
        String bazaarClientId,
        String bazaarClientSecret,
        String bazaarRefreshToken,
        String aiUrl,
        String aiKey,
        String aiModel,
        int maxTokens,
        int dailyLimit,
        Duration recheck,
        int verifyPerIp,
        boolean trustProxy) {

    static Config fromEnv(Map<String, String> env) {
        List<String> missing = new ArrayList<>();
        Config c = new Config(
                Integer.parseInt(env.getOrDefault("PORT", "8080")),
                Path.of(env.getOrDefault("DB_PATH", "daybook-plus.db")),
                trimSlash(env.getOrDefault("BAZAAR_URL", Bazaar.BASE)),
                list(env.getOrDefault("PACKAGES", "com.apklama.adhd")),
                list(env.getOrDefault("PRODUCTS", "daybook_plus_monthly")),
                required(env, "BAZAAR_CLIENT_ID", missing),
                required(env, "BAZAAR_CLIENT_SECRET", missing),
                required(env, "BAZAAR_REFRESH_TOKEN", missing),
                trimSlash(required(env, "AI_URL", missing)),
                required(env, "AI_KEY", missing),
                required(env, "AI_MODEL", missing),
                Integer.parseInt(env.getOrDefault("AI_MAX_TOKENS", "2048")),
                Integer.parseInt(env.getOrDefault("DAILY_LIMIT", "100")),
                Duration.ofHours(Long.parseLong(env.getOrDefault("RECHECK_HOURS", "24"))),
                Integer.parseInt(env.getOrDefault("VERIFY_PER_IP", "20")),
                Boolean.parseBoolean(env.getOrDefault("TRUST_PROXY", "false")));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing settings: " + String.join(", ", missing));
        }
        return c;
    }

    private static String required(Map<String, String> env, String name, List<String> missing) {
        String v = env.getOrDefault(name, "").trim();
        if (v.isEmpty()) missing.add(name);
        return v;
    }

    private static Set<String> list(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String trimSlash(String url) {
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }
}
