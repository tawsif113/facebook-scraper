package com.fbscraper;

import com.fbscraper.client.fb.FacebookClient;
import com.fbscraper.config.AppConfig;
import com.fbscraper.model.facebook.FacebookComment;
import com.fbscraper.model.facebook.FacebookConversation;
import com.fbscraper.model.facebook.FacebookMessage;
import com.fbscraper.model.facebook.FacebookPageRatingSummary;
import com.fbscraper.model.facebook.FacebookPost;
import com.fbscraper.model.facebook.FacebookReaction;
import com.fbscraper.model.facebook.FacebookReactionSummary;
import com.fbscraper.model.facebook.FacebookReview;
import com.fbscraper.model.facebook.FacebookSyncResult;
import com.fbscraper.model.facebook.FacebookUser;
import com.fbscraper.sentiment.VaderAnalyzer;
import com.fbscraper.service.DataExportService;
import com.fbscraper.service.SentimentSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppE2ETest {

    @Test
    void shouldRunTheDashboardSyncPipeline(@TempDir Path tempDir) {
        AppConfig config = new AppConfig("123", "token", "v26.0", 100, 100, 5, -0.05);
        Instant now = Instant.parse("2026-09-08T08:00:00Z");
        FacebookUser commenter = new FacebookUser("u10", "John Doe");
        FacebookUser replier = new FacebookUser("u11", "Replier Jane");
        FacebookReaction commentReactor = new FacebookReaction("u12", "Charlie", "LOVE");
        FacebookReaction postReactor = new FacebookReaction("u13", "Dave", "LIKE");
        FacebookUser reviewer = new FacebookUser("u14", "Reviewer Eve");

        FacebookComment reply = new FacebookComment("r1", "I disagree, it was okay", now, FacebookReactionSummary.empty(), replier, List.of(), List.of());

        FacebookComment comment = new FacebookComment(
                "c1",
                "This service is horrible",
                now,
                new FacebookReactionSummary(3, 1, 0, 1, 0, 0, 0, 1),
                commenter,
                List.of(reply),
                List.of(commentReactor)
        );
        FacebookPost post = new FacebookPost(
                "p1",
                "Support update",
                now,
                List.of(comment),
                new FacebookReactionSummary(10, 4, 1, 1, 1, 0, 1, 2),
                List.of(postReactor)
        );
        FacebookReview review = new FacebookReview(now, "negative", "Very poor support", 2, true, reviewer);
        FacebookUser customer = new FacebookUser("u1", "Bob Customer");
        FacebookUser page = new FacebookUser("123", "Support Page");
        FacebookMessage customerMsg = new FacebookMessage("m1", "My order is terribly broken!", now, customer, List.of(page));
        FacebookMessage pageMsg = new FacebookMessage("m2", "We apologize for the inconvenience", now.plusSeconds(60), page, List.of(customer));
        FacebookConversation conversation = new FacebookConversation("t1", now.plusSeconds(60), List.of(customer, page), List.of(customerMsg, pageMsg));

        FacebookClient client = new FacebookClient(config, request -> {
            throw new AssertionError("The test client must not make HTTP calls");
        }) {
            @Override
            public List<FacebookPost> fetchPageFeed() {
                return List.of(post);
            }

            @Override
            public FacebookPageRatingSummary fetchPageRatingSummary() {
                return new FacebookPageRatingSummary(3.8, 42);
            }

            @Override
            public List<FacebookReview> fetchPageReviews() {
                return List.of(review);
            }

            @Override
            public List<FacebookConversation> fetchPageConversations() {
                return List.of(conversation);
            }
        };

        FacebookSyncResult result = new SentimentSyncService(
                config,
                client,
                VaderAnalyzer.createDefault(),
                new DataExportService(tempDir)
        ).syncFacebook();

        assertThat(result.totalPosts()).isEqualTo(1);
        assertThat(result.totalComments()).isEqualTo(1);
        assertThat(result.totalReactions()).isEqualTo(10);
        assertThat(result.reactionTotals().care()).isEqualTo(1);
        assertThat(result.postReactions().get(0).userReactions()).hasSize(1);
        assertThat(result.postReactions().get(0).userReactions().get(0).name()).isEqualTo("Dave");

        assertThat(result.totalCommentReactions()).isEqualTo(3);
        var analyzedComment = result.comments().get(0);
        assertThat(analyzedComment.reactions().care()).isEqualTo(1);
        assertThat(analyzedComment.from().name()).isEqualTo("John Doe");
        assertThat(analyzedComment.userReactions()).hasSize(1);
        assertThat(analyzedComment.userReactions().get(0).name()).isEqualTo("Charlie");
        assertThat(analyzedComment.replies()).hasSize(1);
        assertThat(analyzedComment.replies().get(0).from().name()).isEqualTo("Replier Jane");

        assertThat(result.totalReviews()).isEqualTo(1);
        assertThat(result.negativeReviews()).isEqualTo(1);
        assertThat(result.reviews().get(0).reviewer().name()).isEqualTo("Reviewer Eve");
        assertThat(result.pageRating().overallStarRating()).isEqualTo(3.8);
        assertThat(result.messageSummary().totalConversations()).isEqualTo(1);
        assertThat(result.messageSummary().totalMessages()).isEqualTo(2);
        assertThat(result.messageSummary().customerMessages()).isEqualTo(1);
        assertThat(result.messageSummary().pageReplies()).isEqualTo(1);
        assertThat(result.messageSummary().negativeRate()).isEqualTo(100.0);
        assertThat(result.conversations()).hasSize(1);
        assertThat(result.conversations().get(0).messages().get(0).isFromPage()).isFalse();
        assertThat(result.conversations().get(0).messages().get(0).flagged()).isTrue();
        assertThat(result.conversations().get(0).messages().get(1).isFromPage()).isTrue();
    }
}
