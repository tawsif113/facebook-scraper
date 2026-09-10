package com.fbscraper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "x")
public record XConfig(
        @DefaultValue("") String bearerToken,
        @DefaultValue("") String username,
        @DefaultValue("10") int postLimit,
        @DefaultValue("100") int replyLimit,
        @DefaultValue("100") int engagementUserLimit,
        @DefaultValue("true") boolean fetchLikingUsers,
        @DefaultValue("true") boolean fetchRepostingUsers
) {
    public XConfig {
        bearerToken = bearerToken == null ? "" : bearerToken.trim();
        username = normalizeUsername(username);
        postLimit = clamp(postLimit, 5, 100);
        replyLimit = clamp(replyLimit, 10, 100);
        engagementUserLimit = clamp(engagementUserLimit, 1, 100);
    }

    public boolean hasBearerToken() {
        return !bearerToken.isBlank();
    }

    public boolean hasUsername() {
        return !username.isBlank();
    }

    public String resolveUsername(String override) {
        String normalized = normalizeUsername(override);
        return normalized.isBlank() ? username : normalized;
    }

    private static String normalizeUsername(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        return normalized.trim();
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        return Math.min(value, max);
    }
}
