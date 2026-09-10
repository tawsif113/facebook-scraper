package com.fbscraper.model.x;

import java.time.Instant;
import java.util.List;

public record XSyncResult(
        Instant syncedAt,
        double negativeThreshold,
        XUser targetUser,
        int totalPosts,
        int totalComments,
        int totalLikes,
        int totalReposts,
        int totalQuotes,
        int positiveComments,
        int neutralComments,
        int warningComments,
        int criticalComments,
        int negativeComments,
        double negativeRate,
        List<XReplyAnalysis> comments,
        List<XPostAnalysis> postReactions,
        List<String> warnings
) {
    public XSyncResult {
        syncedAt = syncedAt == null ? Instant.now() : syncedAt;
        targetUser = targetUser == null ? XUser.minimal("") : targetUser;
        comments = comments == null ? List.of() : List.copyOf(comments);
        postReactions = postReactions == null ? List.of() : List.copyOf(postReactions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
