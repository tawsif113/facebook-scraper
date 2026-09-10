package com.fbscraper.service;

import com.fbscraper.config.AppConfig;
import com.fbscraper.config.XConfig;
import com.fbscraper.enums.SentimentLevel;
import com.fbscraper.client.XClient;
import com.fbscraper.model.SentimentScore;
import com.fbscraper.model.x.XPost;
import com.fbscraper.model.x.XPostAnalysis;
import com.fbscraper.model.x.XReply;
import com.fbscraper.model.x.XReplyAnalysis;
import com.fbscraper.model.x.XSyncResult;
import com.fbscraper.model.x.XUser;
import com.fbscraper.sentiment.VaderAnalyzer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class XSyncService {

    private final XConfig xConfig;
    private final AppConfig appConfig;
    private final XClient xClient;
    private final VaderAnalyzer analyzer;
    private final XDataExportService exportService;

    public XSyncService(
            XConfig xConfig,
            AppConfig appConfig,
            XClient xClient,
            VaderAnalyzer analyzer,
            XDataExportService exportService
    ) {
        this.xConfig = xConfig;
        this.appConfig = appConfig;
        this.xClient = xClient;
        this.analyzer = analyzer;
        this.exportService = exportService;
    }

    public XSyncResult sync(String usernameOverride) {
        XUser targetUser = xClient.fetchUser(usernameOverride);
        List<XPost> posts = xClient.fetchUserPosts(targetUser);
        List<XReplyAnalysis> analyzedReplies = new ArrayList<>();
        List<XPostAnalysis> analyzedPosts = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();

        for (XPost post : posts) {
            String snippet = createSnippet(post.text());
            SentimentScore postScore = analyzer.analyze(post.text());

            List<XUser> likingUsers = List.of();
            if (xConfig.fetchLikingUsers() && post.metrics().likeCount() > 0) {
                try {
                    likingUsers = xClient.fetchLikingUsers(post.id());
                    if (post.metrics().likeCount() > xConfig.engagementUserLimit()) {
                        warnings.add("Some liking-user lists are truncated to " + xConfig.engagementUserLimit() + " users per post.");
                    }
                } catch (RuntimeException e) {
                    warnings.add("Liking-user identities could not be loaded for one or more posts: " + shortMessage(e));
                }
            }

            List<XUser> repostingUsers = List.of();
            if (xConfig.fetchRepostingUsers() && post.metrics().repostCount() > 0) {
                try {
                    repostingUsers = xClient.fetchRepostingUsers(post.id());
                    if (post.metrics().repostCount() > xConfig.engagementUserLimit()) {
                        warnings.add("Some reposting-user lists are truncated to " + xConfig.engagementUserLimit() + " users per post.");
                    }
                } catch (RuntimeException e) {
                    warnings.add("Reposting-user identities could not be loaded for one or more posts: " + shortMessage(e));
                }
            }

            analyzedPosts.add(new XPostAnalysis(
                    post.id(),
                    snippet,
                    post.text(),
                    post.createdTime(),
                    permalink(targetUser.username(), post.id()),
                    postScore.compound(),
                    postScore.level(),
                    postScore.compound() <= appConfig.negativeThreshold(),
                    post.metrics().likeCount(),
                    post.metrics().replyCount(),
                    post.metrics().repostCount(),
                    post.metrics().quoteCount(),
                    likingUsers,
                    repostingUsers
            ));

            if (post.metrics().replyCount() <= 0) {
                continue;
            }

            if (Duration.between(post.createdTime(), Instant.now()).toDays() >= 7) {
                warnings.add("Reply details use X Recent Search, so replies older than 7 days may not be returned for older posts.");
            }
            if (post.metrics().replyCount() > xConfig.replyLimit()) {
                warnings.add("Some reply lists are truncated to " + xConfig.replyLimit() + " replies per post.");
            }

            try {
                List<XReply> replies = xClient.fetchReplies(post.conversationId());
                for (XReply reply : replies) {
                    SentimentScore score = analyzer.analyze(reply.text());
                    analyzedReplies.add(new XReplyAnalysis(
                            reply.id(),
                            post.id(),
                            snippet,
                            reply.text(),
                            reply.createdTime(),
                            score.compound(),
                            score.level(),
                            score.compound() <= appConfig.negativeThreshold(),
                            reply.metrics().likeCount(),
                            reply.author(),
                            reply.parentPostId()
                    ));
                }
            } catch (RuntimeException e) {
                warnings.add("Reply details could not be loaded for one or more posts: " + shortMessage(e));
            }
        }

        analyzedPosts.sort(Comparator.comparing(XPostAnalysis::createdTime).reversed());
        analyzedReplies.sort(Comparator.comparing(XReplyAnalysis::createdTime).reversed());

        int positive = (int) analyzedReplies.stream().filter(r -> r.level() == SentimentLevel.POSITIVE).count();
        int neutral = (int) analyzedReplies.stream().filter(r -> r.level() == SentimentLevel.NEUTRAL).count();
        int warning = (int) analyzedReplies.stream().filter(r -> r.level() == SentimentLevel.WARNING_NEGATIVE).count();
        int critical = (int) analyzedReplies.stream().filter(r -> r.level() == SentimentLevel.CRITICAL_NEGATIVE).count();
        int negative = (int) analyzedReplies.stream().filter(XReplyAnalysis::flagged).count();
        double negativeRate = analyzedReplies.isEmpty()
                ? 0.0
                : Math.round((negative * 1000.0) / analyzedReplies.size()) / 10.0;

        int totalLikes = posts.stream().mapToInt(p -> p.metrics().likeCount()).sum();
        int totalReposts = posts.stream().mapToInt(p -> p.metrics().repostCount()).sum();
        int totalQuotes = posts.stream().mapToInt(p -> p.metrics().quoteCount()).sum();

        XSyncResult result = new XSyncResult(
                Instant.now(),
                appConfig.negativeThreshold(),
                targetUser,
                posts.size(),
                analyzedReplies.size(),
                totalLikes,
                totalReposts,
                totalQuotes,
                positive,
                neutral,
                warning,
                critical,
                negative,
                negativeRate,
                analyzedReplies,
                analyzedPosts,
                new ArrayList<>(warnings)
        );
        exportService.export(result);
        return result;
    }

    public Optional<XSyncResult> loadPreviousResult() {
        return exportService.loadLatest();
    }

    private String permalink(String username, String postId) {
        if (username == null || username.isBlank()) {
            return "https://x.com/i/status/" + postId;
        }
        return "https://x.com/" + username + "/status/" + postId;
    }

    private String createSnippet(String text) {
        if (text == null || text.isBlank()) {
            return "[No post text]";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 100 ? normalized.substring(0, 97) + "..." : normalized;
    }

    private String shortMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "X API request failed";
        }
        return message.length() > 220 ? message.substring(0, 217) + "..." : message;
    }
}
