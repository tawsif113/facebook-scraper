package com.fbscraper.service;

import com.fbscraper.client.fb.FacebookClient;
import com.fbscraper.client.ig.InstagramClient;
import com.fbscraper.config.AppConfig;
import com.fbscraper.enums.SentimentLevel;
import com.fbscraper.model.MessageSentimentSummary;
import com.fbscraper.model.SentimentScore;
import com.fbscraper.model.facebook.FacebookComment;
import com.fbscraper.model.facebook.FacebookCommentAnalysis;
import com.fbscraper.model.facebook.FacebookConversation;
import com.fbscraper.model.facebook.FacebookMessage;
import com.fbscraper.model.facebook.FacebookPageRatingSummary;
import com.fbscraper.model.facebook.FacebookPost;
import com.fbscraper.model.facebook.FacebookPostReactionAnalysis;
import com.fbscraper.model.facebook.FacebookReactionSummary;
import com.fbscraper.model.facebook.FacebookReview;
import com.fbscraper.model.facebook.FacebookSyncResult;
import com.fbscraper.model.instagram.InstagramComment;
import com.fbscraper.model.instagram.InstagramCommentAnalysis;
import com.fbscraper.model.instagram.InstagramConversation;
import com.fbscraper.model.instagram.InstagramMedia;
import com.fbscraper.model.instagram.InstagramMediaAnalysis;
import com.fbscraper.model.instagram.InstagramMessage;
import com.fbscraper.model.instagram.InstagramSyncResult;
import com.fbscraper.sentiment.VaderAnalyzer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class SentimentSyncService {

    private record FacebookConversationAnalysisResult(
            List<FacebookConversation> conversations,
            MessageSentimentSummary summary
    ) {}

    private record FacebookPostAnalysisResult(
            List<FacebookCommentAnalysis> comments,
            List<FacebookPostReactionAnalysis> postReactions,
            int positive,
            int neutral,
            int warning,
            int critical,
            int negative,
            double negativeRate,
            FacebookReactionSummary reactionTotals,
            FacebookReactionSummary commentReactionTotals
    ) {}

    private record InstagramConversationAnalysisResult(
            List<InstagramConversation> conversations,
            MessageSentimentSummary summary
    ) {}

    private record InstagramMediaAnalysisResult(
            List<InstagramCommentAnalysis> comments,
            List<InstagramMediaAnalysis> media,
            int positive,
            int neutral,
            int warning,
            int critical,
            int negative,
            double negativeRate,
            int totalLikes,
            int totalComments
    ) {}

    private final FacebookClient facebookClient;
    private final InstagramClient instagramClient;
    private final VaderAnalyzer analyzer;
    private final AppConfig config;
    private final DataExportService dataExportService;

    public SentimentSyncService(AppConfig config) {
        this(config, new FacebookClient(config), new InstagramClient(config), VaderAnalyzer.createDefault(), new DataExportService());
    }

    public SentimentSyncService(AppConfig config, FacebookClient facebookClient, VaderAnalyzer analyzer) {
        this(config, facebookClient, new InstagramClient(config), analyzer, new DataExportService());
    }

    public SentimentSyncService(FacebookClient facebookClient, AppConfig config) {
        this(config, facebookClient, new InstagramClient(config), VaderAnalyzer.createDefault(), new DataExportService());
    }

    public SentimentSyncService(
            AppConfig config,
            FacebookClient facebookClient,
            VaderAnalyzer analyzer,
            DataExportService dataExportService
    ) {
        this(config, facebookClient, new InstagramClient(config), analyzer, dataExportService);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SentimentSyncService(
            AppConfig config,
            FacebookClient facebookClient,
            InstagramClient instagramClient,
            VaderAnalyzer analyzer,
            DataExportService dataExportService
    ) {
        this.config = config;
        this.facebookClient = facebookClient;
        this.instagramClient = instagramClient;
        this.analyzer = analyzer;
        this.dataExportService = dataExportService;
    }

    public FacebookClient getFacebookClient() {
        return facebookClient;
    }

    public InstagramClient getInstagramClient() {
        return instagramClient;
    }

    public VaderAnalyzer getAnalyzer() {
        return analyzer;
    }

    public AppConfig getConfig() {
        return config;
    }

    public DataExportService getDataExportService() {
        return dataExportService;
    }

    public Optional<FacebookSyncResult> loadPreviousResult() {
        return loadPreviousFacebookResult();
    }

    public Optional<FacebookSyncResult> loadPreviousFacebookResult() {
        return dataExportService.loadLatestFacebook(config.negativeThreshold());
    }

    public Optional<InstagramSyncResult> loadPreviousInstagramResult() {
        return dataExportService.loadLatestInstagram(config.negativeThreshold());
    }

    public FacebookSyncResult sync() {
        return syncFacebook();
    }

    public FacebookSyncResult syncFacebook() {
        List<FacebookPost> posts = facebookClient.fetchPageFeed();
        FacebookPageRatingSummary initialRating = facebookClient.fetchPageRatingSummary();
        List<FacebookReview> fetchedReviews = facebookClient.fetchPageReviews();
        List<FacebookConversation> fetchedConversations = facebookClient.fetchPageConversations();

        FacebookConversationAnalysisResult convResult = analyzeFacebookConversations(fetchedConversations, config.pageId());
        FacebookPostAnalysisResult postResult = analyzeFacebookPosts(posts);

        List<FacebookReview> reviews = new ArrayList<>();
        for (FacebookReview review : fetchedReviews) {
            reviews.add(new FacebookReview(
                    review.createdTime(),
                    review.recommendationType(),
                    review.reviewText(),
                    review.rating(),
                    review.hasReview(),
                    review.reviewer(),
                    analyzeFacebookReview(review)
            ));
        }
        reviews.sort(Comparator.comparing(FacebookReview::createdTime, Comparator.reverseOrder()));

        int negativeReviews = (int) reviews.stream()
                .filter(review -> review.score() != null && review.score().compound() <= config.negativeThreshold())
                .count();
        int yesCount = (int) reviews.stream()
                .filter(FacebookReview::isPositiveRecommendation)
                .count();
        int noCount = (int) reviews.stream()
                .filter(FacebookReview::isNegativeRecommendation)
                .count();
        int totalReviews = reviews.size();

        int updatedRatingCount = initialRating.ratingCount() > 0
                ? Math.max(initialRating.ratingCount(), totalReviews)
                : (yesCount + noCount > 0 ? (yesCount + noCount) : totalReviews);

        double overallStarRating = initialRating.hasRatings() && initialRating.overallStarRating() > 0.0
                ? initialRating.overallStarRating()
                : (totalReviews > 0 ? Math.round(((double) yesCount / totalReviews) * 50.0) / 10.0 : 0.0);

        FacebookPageRatingSummary pageRating = new FacebookPageRatingSummary(
                overallStarRating,
                updatedRatingCount,
                yesCount,
                noCount
        );

        FacebookSyncResult result = new FacebookSyncResult(
                Instant.now(),
                config.negativeThreshold(),
                posts.size(),
                postResult.comments().size(),
                postResult.reactionTotals().total(),
                postResult.reactionTotals(),
                postResult.commentReactionTotals().total(),
                postResult.commentReactionTotals(),
                postResult.positive(),
                postResult.neutral(),
                postResult.warning(),
                postResult.critical(),
                postResult.negative(),
                postResult.negativeRate(),
                postResult.comments(),
                postResult.postReactions(),
                pageRating,
                reviews.size(),
                negativeReviews,
                reviews,
                convResult.summary(),
                convResult.conversations()
        );

        dataExportService.exportFacebook(result);
        return result;
    }

    public InstagramSyncResult syncInstagram() {
        List<InstagramMedia> media = instagramClient.fetchMedia();
        List<InstagramConversation> fetchedConversations = instagramClient.fetchConversations();
        String igUserId = "";
        try {
            igUserId = instagramClient.resolveBusinessAccountId();
        } catch (Exception ignored) {
        }

        InstagramConversationAnalysisResult convResult = analyzeInstagramConversations(fetchedConversations, igUserId);
        InstagramMediaAnalysisResult mediaResult = analyzeInstagramMedia(media);

        InstagramSyncResult result = new InstagramSyncResult(
                Instant.now(),
                config.negativeThreshold(),
                mediaResult.media().size(),
                mediaResult.comments().size(),
                mediaResult.totalLikes(),
                mediaResult.positive(),
                mediaResult.neutral(),
                mediaResult.warning(),
                mediaResult.critical(),
                mediaResult.negative(),
                mediaResult.negativeRate(),
                mediaResult.comments(),
                mediaResult.media(),
                convResult.summary(),
                convResult.conversations()
        );

        dataExportService.exportInstagram(result);
        return result;
    }

    private FacebookConversationAnalysisResult analyzeFacebookConversations(
            List<FacebookConversation> fetchedConversations,
            String pageId
    ) {
        List<FacebookConversation> conversations = new ArrayList<>();
        int totalCustomerMessages = 0;
        int totalPageReplies = 0;
        int msgPositive = 0;
        int msgNeutral = 0;
        int msgWarning = 0;
        int msgCritical = 0;

        for (FacebookConversation conv : fetchedConversations) {
            List<FacebookMessage> analyzedMessages = new ArrayList<>();
            int custCount = 0;
            int pageCount = 0;
            SentimentLevel worstLevel = null;

            List<FacebookMessage> sortedMessages = new ArrayList<>(conv.messages());
            sortedMessages.sort(Comparator.comparing(FacebookMessage::createdTime));

            for (FacebookMessage msg : sortedMessages) {
                boolean isFromPage = msg.from() != null
                        && pageId != null
                        && !pageId.isBlank()
                        && pageId.equals(msg.from().id());

                if (isFromPage) {
                    pageCount++;
                    totalPageReplies++;
                    analyzedMessages.add(new FacebookMessage(
                            msg.id(),
                            msg.message(),
                            msg.createdTime(),
                            msg.from(),
                            msg.to(),
                            msg.attachments(),
                            true,
                            null,
                            false
                    ));
                } else {
                    custCount++;
                    totalCustomerMessages++;
                    SentimentScore score = analyzer.analyze(msg.message());
                    boolean flagged = score.compound() <= config.negativeThreshold();

                    switch (score.level()) {
                        case POSITIVE -> msgPositive++;
                        case NEUTRAL -> msgNeutral++;
                        case WARNING_NEGATIVE -> msgWarning++;
                        case CRITICAL_NEGATIVE -> msgCritical++;
                    }

                    worstLevel = prioritizeSentiment(worstLevel, score.level());

                    analyzedMessages.add(new FacebookMessage(
                            msg.id(),
                            msg.message(),
                            msg.createdTime(),
                            msg.from(),
                            msg.to(),
                            msg.attachments(),
                            false,
                            score,
                            flagged
                    ));
                }
            }

            SentimentLevel threadSentiment = worstLevel != null ? worstLevel : SentimentLevel.NEUTRAL;
            conversations.add(new FacebookConversation(
                    conv.id(),
                    conv.updatedTime(),
                    conv.participants(),
                    analyzedMessages,
                    threadSentiment,
                    custCount,
                    pageCount
            ));
        }

        conversations.sort(Comparator.comparing(FacebookConversation::updatedTime).reversed());

        int totalMsgNegative = msgWarning + msgCritical;
        double messageNegativeRate = totalCustomerMessages == 0
                ? 0.0
                : Math.round((totalMsgNegative * 1000.0) / totalCustomerMessages) / 10.0;

        MessageSentimentSummary messageSummary = new MessageSentimentSummary(
                conversations.size(),
                totalCustomerMessages + totalPageReplies,
                totalCustomerMessages,
                totalPageReplies,
                msgPositive,
                msgNeutral,
                msgWarning,
                msgCritical,
                messageNegativeRate
        );

        return new FacebookConversationAnalysisResult(conversations, messageSummary);
    }

    private FacebookPostAnalysisResult analyzeFacebookPosts(List<FacebookPost> posts) {
        List<FacebookCommentAnalysis> comments = new ArrayList<>();
        List<FacebookPostReactionAnalysis> postReactions = new ArrayList<>();

        for (FacebookPost post : posts) {
            String snippet = createSnippet(post.message());
            postReactions.add(new FacebookPostReactionAnalysis(
                    post.id(),
                    snippet,
                    post.createdTime(),
                    post.reactions(),
                    post.userReactions()
            ));

            for (FacebookComment comment : post.comments()) {
                comments.add(analyzeFacebookComment(comment, post.id(), snippet));
            }
        }

        comments.sort(Comparator.comparing(FacebookCommentAnalysis::createdTime).reversed());
        postReactions.sort(Comparator.comparing(FacebookPostReactionAnalysis::createdTime).reversed());

        int positive = (int) comments.stream().filter(c -> c.level() == SentimentLevel.POSITIVE).count();
        int neutral = (int) comments.stream().filter(c -> c.level() == SentimentLevel.NEUTRAL).count();
        int warning = (int) comments.stream().filter(c -> c.level() == SentimentLevel.WARNING_NEGATIVE).count();
        int critical = (int) comments.stream().filter(c -> c.level() == SentimentLevel.CRITICAL_NEGATIVE).count();
        int negative = (int) comments.stream().filter(FacebookCommentAnalysis::flagged).count();
        double negativeRate = comments.isEmpty()
                ? 0.0
                : Math.round((negative * 1000.0) / comments.size()) / 10.0;

        FacebookReactionSummary reactionTotals = sumFacebookReactions(posts);
        FacebookReactionSummary commentReactionTotals = sumFacebookCommentReactions(posts);

        return new FacebookPostAnalysisResult(
                comments,
                postReactions,
                positive,
                neutral,
                warning,
                critical,
                negative,
                negativeRate,
                reactionTotals,
                commentReactionTotals
        );
    }

    private FacebookCommentAnalysis analyzeFacebookComment(FacebookComment comment, String postId, String postSnippet) {
        SentimentScore score = analyzer.analyze(comment.message());
        List<FacebookCommentAnalysis> replies = new ArrayList<>();
        for (FacebookComment reply : comment.replies()) {
            replies.add(analyzeFacebookComment(reply, postId, postSnippet));
        }
        return new FacebookCommentAnalysis(
                comment.id(),
                postId,
                postSnippet,
                comment.message(),
                comment.createdTime(),
                score.compound(),
                score.level(),
                score.compound() <= config.negativeThreshold(),
                comment.reactions(),
                comment.from(),
                replies,
                comment.userReactions()
        );
    }

    private SentimentScore analyzeFacebookReview(FacebookReview review) {
        String text = review.reviewText() == null ? "" : review.reviewText();
        if (!text.isBlank()) {
            return analyzer.analyze(text);
        }
        if (review.isPositiveRecommendation()) {
            return new SentimentScore(0.5, 0.5, 0.5, 0.0, SentimentLevel.POSITIVE);
        }
        if (review.isNegativeRecommendation()) {
            return new SentimentScore(-0.5, 0.0, 0.5, 0.5, SentimentLevel.CRITICAL_NEGATIVE);
        }
        return new SentimentScore(0.0, 0.0, 1.0, 0.0, SentimentLevel.NEUTRAL);
    }

    private FacebookReactionSummary sumFacebookReactions(List<FacebookPost> posts) {
        return sumFacebookReactionSummaries(posts.stream().map(FacebookPost::reactions).toList());
    }

    private FacebookReactionSummary sumFacebookCommentReactions(List<FacebookPost> posts) {
        List<FacebookReactionSummary> summaries = new ArrayList<>();
        for (FacebookPost post : posts) {
            for (FacebookComment comment : post.comments()) {
                summaries.add(comment.reactions());
                for (FacebookComment reply : comment.replies()) {
                    summaries.add(reply.reactions());
                }
            }
        }
        return sumFacebookReactionSummaries(summaries);
    }

    private FacebookReactionSummary sumFacebookReactionSummaries(List<FacebookReactionSummary> reactions) {
        return new FacebookReactionSummary(
                reactions.stream().mapToInt(FacebookReactionSummary::total).sum(),
                reactions.stream().mapToInt(FacebookReactionSummary::like).sum(),
                reactions.stream().mapToInt(FacebookReactionSummary::love).sum(),
                reactions.stream().mapToInt(FacebookReactionSummary::care).sum(),
                reactions.stream().mapToInt(FacebookReactionSummary::haha).sum(),
                reactions.stream().mapToInt(FacebookReactionSummary::wow).sum(),
                reactions.stream().mapToInt(FacebookReactionSummary::sad).sum(),
                reactions.stream().mapToInt(FacebookReactionSummary::angry).sum()
        );
    }

    private InstagramConversationAnalysisResult analyzeInstagramConversations(
            List<InstagramConversation> fetchedConversations,
            String igUserId
    ) {
        List<InstagramConversation> conversations = new ArrayList<>();
        int totalCustomerMessages = 0;
        int totalPageReplies = 0;
        int msgPositive = 0;
        int msgNeutral = 0;
        int msgWarning = 0;
        int msgCritical = 0;

        for (InstagramConversation conv : fetchedConversations) {
            List<InstagramMessage> analyzedMessages = new ArrayList<>();
            int custCount = 0;
            int pageCount = 0;
            SentimentLevel worstLevel = null;

            List<InstagramMessage> sortedMessages = new ArrayList<>(conv.messages());
            sortedMessages.sort(Comparator.comparing(InstagramMessage::createdTime));

            for (InstagramMessage msg : sortedMessages) {
                boolean isFromPage = msg.from() != null
                        && igUserId != null
                        && !igUserId.isBlank()
                        && igUserId.equals(msg.from().id());

                if (isFromPage) {
                    pageCount++;
                    totalPageReplies++;
                    analyzedMessages.add(new InstagramMessage(
                            msg.id(),
                            msg.message(),
                            msg.createdTime(),
                            msg.from(),
                            msg.to(),
                            msg.attachments(),
                            true,
                            null,
                            false
                    ));
                } else {
                    custCount++;
                    totalCustomerMessages++;
                    SentimentScore score = analyzer.analyze(msg.message());
                    boolean flagged = score.compound() <= config.negativeThreshold();

                    switch (score.level()) {
                        case POSITIVE -> msgPositive++;
                        case NEUTRAL -> msgNeutral++;
                        case WARNING_NEGATIVE -> msgWarning++;
                        case CRITICAL_NEGATIVE -> msgCritical++;
                    }

                    worstLevel = prioritizeSentiment(worstLevel, score.level());

                    analyzedMessages.add(new InstagramMessage(
                            msg.id(),
                            msg.message(),
                            msg.createdTime(),
                            msg.from(),
                            msg.to(),
                            msg.attachments(),
                            false,
                            score,
                            flagged
                    ));
                }
            }

            SentimentLevel threadSentiment = worstLevel != null ? worstLevel : SentimentLevel.NEUTRAL;
            conversations.add(new InstagramConversation(
                    conv.id(),
                    conv.updatedTime(),
                    conv.participants(),
                    analyzedMessages,
                    threadSentiment,
                    custCount,
                    pageCount
            ));
        }

        conversations.sort(Comparator.comparing(InstagramConversation::updatedTime).reversed());

        int totalMsgNegative = msgWarning + msgCritical;
        double messageNegativeRate = totalCustomerMessages == 0
                ? 0.0
                : Math.round((totalMsgNegative * 1000.0) / totalCustomerMessages) / 10.0;

        MessageSentimentSummary messageSummary = new MessageSentimentSummary(
                conversations.size(),
                totalCustomerMessages + totalPageReplies,
                totalCustomerMessages,
                totalPageReplies,
                msgPositive,
                msgNeutral,
                msgWarning,
                msgCritical,
                messageNegativeRate
        );

        return new InstagramConversationAnalysisResult(conversations, messageSummary);
    }

    private InstagramMediaAnalysisResult analyzeInstagramMedia(List<InstagramMedia> mediaList) {
        List<InstagramCommentAnalysis> comments = new ArrayList<>();
        List<InstagramMediaAnalysis> mediaAnalysis = new ArrayList<>();
        int totalLikes = 0;
        int totalComments = 0;

        for (InstagramMedia media : mediaList) {
            totalLikes += media.likeCount();
            totalComments += media.commentsCount();

            String snippet = createSnippet(media.caption());
            mediaAnalysis.add(new InstagramMediaAnalysis(
                    media.id(),
                    snippet,
                    media.mediaType(),
                    media.permalink(),
                    media.timestamp(),
                    media.likeCount(),
                    media.commentsCount()
            ));

            for (InstagramComment comment : media.comments()) {
                comments.add(analyzeInstagramComment(comment, media.id(), snippet));
            }
        }

        comments.sort(Comparator.comparing(InstagramCommentAnalysis::timestamp).reversed());
        mediaAnalysis.sort(Comparator.comparing(InstagramMediaAnalysis::timestamp).reversed());

        int positive = (int) comments.stream().filter(c -> c.level() == SentimentLevel.POSITIVE).count();
        int neutral = (int) comments.stream().filter(c -> c.level() == SentimentLevel.NEUTRAL).count();
        int warning = (int) comments.stream().filter(c -> c.level() == SentimentLevel.WARNING_NEGATIVE).count();
        int critical = (int) comments.stream().filter(c -> c.level() == SentimentLevel.CRITICAL_NEGATIVE).count();
        int negative = (int) comments.stream().filter(InstagramCommentAnalysis::flagged).count();
        double negativeRate = comments.isEmpty()
                ? 0.0
                : Math.round((negative * 1000.0) / comments.size()) / 10.0;

        return new InstagramMediaAnalysisResult(
                comments,
                mediaAnalysis,
                positive,
                neutral,
                warning,
                critical,
                negative,
                negativeRate,
                totalLikes,
                totalComments
        );
    }

    private InstagramCommentAnalysis analyzeInstagramComment(InstagramComment comment, String mediaId, String mediaSnippet) {
        SentimentScore score = analyzer.analyze(comment.text());
        List<InstagramCommentAnalysis> replies = new ArrayList<>();
        for (InstagramComment reply : comment.replies()) {
            replies.add(analyzeInstagramComment(reply, mediaId, mediaSnippet));
        }
        return new InstagramCommentAnalysis(
                comment.id(),
                mediaId,
                mediaSnippet,
                comment.text(),
                comment.timestamp(),
                score.compound(),
                score.level(),
                score.compound() <= config.negativeThreshold(),
                comment.likeCount(),
                comment.from(),
                replies
        );
    }

    private SentimentLevel prioritizeSentiment(SentimentLevel current, SentimentLevel candidate) {
        if (current == null) {
            return candidate;
        }
        if (current == SentimentLevel.CRITICAL_NEGATIVE || candidate == SentimentLevel.CRITICAL_NEGATIVE) {
            return SentimentLevel.CRITICAL_NEGATIVE;
        }
        if (current == SentimentLevel.WARNING_NEGATIVE || candidate == SentimentLevel.WARNING_NEGATIVE) {
            return SentimentLevel.WARNING_NEGATIVE;
        }
        if (current == SentimentLevel.POSITIVE || candidate == SentimentLevel.POSITIVE) {
            return SentimentLevel.POSITIVE;
        }
        return SentimentLevel.NEUTRAL;
    }

    private String createSnippet(String text) {
        if (text == null || text.isBlank()) {
            return "[No text]";
        }
        return text.length() > 90 ? text.substring(0, 87) + "..." : text;
    }
}
