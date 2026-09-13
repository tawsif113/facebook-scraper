package com.fbscraper.model.x;

import com.fbscraper.enums.SentimentLevel;

import java.time.Instant;
import java.util.List;

public record XPostAnalysis(
        String postId,
        String postSnippet,
        String text,
        Instant createdTime,
        String permalink,
        double score,
        SentimentLevel level,
        boolean flagged,
        int likeCount,
        int replyCount,
        int repostCount,
        int quoteCount,
        XUser author,
        List<XUser> likingUsers,
        List<XUser> repostingUsers
) {
    public XPostAnalysis {
        postId = postId == null ? "" : postId;
        postSnippet = postSnippet == null ? "" : postSnippet;
        text = text == null ? "" : text;
        createdTime = createdTime == null ? Instant.EPOCH : createdTime;
        permalink = permalink == null ? "" : permalink;
        level = level == null ? SentimentLevel.NEUTRAL : level;
        author = author == null ? XUser.minimal("") : author;
        likingUsers = likingUsers == null ? List.of() : List.copyOf(likingUsers);
        repostingUsers = repostingUsers == null ? List.of() : List.copyOf(repostingUsers);
    }
}
