package com.fbscraper.model.x;

import java.time.Instant;

public record XPost(
        String id,
        String text,
        XUser author,
        Instant createdTime,
        String conversationId,
        String language,
        XPublicMetrics metrics
) {
    public XPost {
        id = id == null ? "" : id;
        text = text == null ? "" : text;
        author = author == null ? XUser.minimal("") : author;
        createdTime = createdTime == null ? Instant.EPOCH : createdTime;
        conversationId = conversationId == null || conversationId.isBlank() ? id : conversationId;
        language = language == null ? "" : language;
        metrics = metrics == null ? XPublicMetrics.EMPTY : metrics;
    }
}
