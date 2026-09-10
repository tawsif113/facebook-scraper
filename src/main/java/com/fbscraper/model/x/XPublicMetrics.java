package com.fbscraper.model.x;

public record XPublicMetrics(
        int likeCount,
        int replyCount,
        int repostCount,
        int quoteCount,
        int impressionCount
) {
    public static final XPublicMetrics EMPTY = new XPublicMetrics(0, 0, 0, 0, 0);
}
