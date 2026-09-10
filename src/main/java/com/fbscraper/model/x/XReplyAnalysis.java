package com.fbscraper.model.x;

import com.fbscraper.enums.SentimentLevel;

import java.time.Instant;

public record XReplyAnalysis(
        String id,
        String postId,
        String postSnippet,
        String text,
        Instant createdTime,
        double score,
        SentimentLevel level,
        boolean flagged,
        int likeCount,
        XUser author,
        String parentPostId
) {
    public XReplyAnalysis {
        id = id == null ? "" : id;
        postId = postId == null ? "" : postId;
        postSnippet = postSnippet == null ? "" : postSnippet;
        text = text == null ? "" : text;
        createdTime = createdTime == null ? Instant.EPOCH : createdTime;
        level = level == null ? SentimentLevel.NEUTRAL : level;
        author = author == null ? XUser.minimal("") : author;
        parentPostId = parentPostId == null ? "" : parentPostId;
    }
}
