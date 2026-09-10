package com.fbscraper.model.x;

import java.time.Instant;

public record XReply(
        String id,
        String text,
        XUser author,
        Instant createdTime,
        String conversationId,
        String parentPostId,
        XPublicMetrics metrics
) {
    public XReply {
        id = id == null ? "" : id;
        text = text == null ? "" : text;
        author = author == null ? XUser.minimal("") : author;
        createdTime = createdTime == null ? Instant.EPOCH : createdTime;
        conversationId = conversationId == null ? "" : conversationId;
        parentPostId = parentPostId == null ? "" : parentPostId;
        metrics = metrics == null ? XPublicMetrics.EMPTY : metrics;
    }
}
