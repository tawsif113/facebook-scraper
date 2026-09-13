package com.fbscraper.model;

import com.fbscraper.enums.SentimentLevel;
import com.fbscraper.model.facebook.FacebookComment;
import com.fbscraper.model.facebook.FacebookConversation;
import com.fbscraper.model.facebook.FacebookMessage;
import com.fbscraper.model.facebook.FacebookPageRatingSummary;
import com.fbscraper.model.facebook.FacebookPost;
import com.fbscraper.model.facebook.FacebookReaction;
import com.fbscraper.model.facebook.FacebookReactionSummary;
import com.fbscraper.model.facebook.FacebookReview;
import com.fbscraper.model.facebook.FacebookUser;
import com.fbscraper.model.instagram.InstagramComment;
import com.fbscraper.model.instagram.InstagramConversation;
import com.fbscraper.model.instagram.InstagramMedia;
import com.fbscraper.model.instagram.InstagramMessage;
import com.fbscraper.model.instagram.InstagramUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelTest {

    @Test
    void shouldCreateAndVerifySentimentScore() {
        SentimentScore score = new SentimentScore(-0.65, 0.05, 0.20, 0.75, SentimentLevel.CRITICAL_NEGATIVE);

        assertThat(score.compound()).isEqualTo(-0.65);
        assertThat(score.isNegative()).isTrue();
        assertThat(score.level()).isEqualTo(SentimentLevel.CRITICAL_NEGATIVE);
    }

    @Test
    void shouldCreateFacebookPostWithComments() {
        Instant now = Instant.now();
        FacebookComment comment = new FacebookComment("c1", "Terrible service!", now);
        FacebookReactionSummary reactions = new FacebookReactionSummary(12, 6, 2, 1, 1, 0, 1, 1);
        FacebookPost post = new FacebookPost("p1", "Check our new product", now, List.of(comment), reactions);

        assertThat(post.id()).isEqualTo("p1");
        assertThat(post.comments()).hasSize(1);
        assertThat(post.comments().get(0).message()).isEqualTo("Terrible service!");
        assertThat(post.comments().get(0).reactions()).isEqualTo(FacebookReactionSummary.empty());
        assertThat(post.reactions().total()).isEqualTo(12);
        assertThat(post.reactions().angry()).isEqualTo(1);
    }

    @Test
    void shouldStoreFacebookCommentReactions() {
        FacebookReactionSummary reactions = new FacebookReactionSummary(3, 1, 0, 2, 0, 0, 0, 0);
        FacebookComment comment = new FacebookComment("c2", "Thanks", Instant.now(), reactions);

        assertThat(comment.reactions().total()).isEqualTo(3);
        assertThat(comment.reactions().care()).isEqualTo(2);
    }

    @Test
    void shouldHandleNullCommentsGracefullyInFacebookPost() {
        FacebookPost post = new FacebookPost("p2", "Post without comments", Instant.now(), null, null);
        assertThat(post.comments()).isNotNull().isEmpty();
        assertThat(post.reactions()).isEqualTo(FacebookReactionSummary.empty());
    }

    @Test
    void shouldCreateAndVerifyFacebookPageRatingSummary() {
        FacebookPageRatingSummary summary = new FacebookPageRatingSummary(4.6, 120);
        assertThat(summary.overallStarRating()).isEqualTo(4.6);
        assertThat(summary.ratingCount()).isEqualTo(120);
        assertThat(summary.hasRatings()).isTrue();

        assertThat(FacebookPageRatingSummary.EMPTY.hasRatings()).isFalse();
    }

    @Test
    void shouldCreateAndVerifyFacebookReview() {
        Instant now = Instant.now();
        SentimentScore score = new SentimentScore(0.62, 0.4, 0.6, 0.0, SentimentLevel.POSITIVE);
        FacebookReview review = new FacebookReview(now, "positive", "Great staff!", 5, true, FacebookUser.ANONYMOUS, score);
        assertThat(review.isPositiveRecommendation()).isTrue();
        assertThat(review.isNegativeRecommendation()).isFalse();
        assertThat(review.reviewText()).isEqualTo("Great staff!");
        assertThat(review.rating()).isEqualTo(5);
        assertThat(review.score().compound()).isEqualTo(0.62);
    }

    @Test
    void shouldCreateAndVerifyFacebookMessengerModels() {
        Instant now = Instant.now();
        FacebookUser user = new FacebookUser("u1", "Jane Doe", "jane@example.com");
        FacebookUser page = new FacebookUser("p1", "My Page", null);

        SentimentScore score = new SentimentScore(-0.6, 0.1, 0.3, 0.6, SentimentLevel.CRITICAL_NEGATIVE);
        FacebookMessage msg = new FacebookMessage("m1", "Need help with order", now, user, List.of(page), List.of(), false, score, true);
        FacebookConversation conv = new FacebookConversation(
                "t1", now, List.of(user, page), List.of(msg), SentimentLevel.CRITICAL_NEGATIVE, 1, 0
        );

        assertThat(conv.id()).isEqualTo("t1");
        assertThat(conv.messages()).hasSize(1);
        assertThat(conv.messages().get(0).from().name()).isEqualTo("Jane Doe");
        assertThat(conv.messages().get(0).isFromPage()).isFalse();
        assertThat(conv.messages().get(0).flagged()).isTrue();
        assertThat(conv.overallSentiment()).isEqualTo(SentimentLevel.CRITICAL_NEGATIVE);
        assertThat(conv.customerMessageCount()).isEqualTo(1);
        assertThat(conv.pageMessageCount()).isEqualTo(0);

        MessageSentimentSummary summary = new MessageSentimentSummary(1, 1, 1, 0, 0, 0, 0, 1, 100.0);
        assertThat(summary.criticalMessages()).isEqualTo(1);
        assertThat(summary.negativeRate()).isEqualTo(100.0);
    }

    @Test
    void shouldCreateAndVerifyFacebookUserAndReaction() {
        FacebookUser user = new FacebookUser("u100", "Alice Wonderland");
        assertThat(user.id()).isEqualTo("u100");
        assertThat(user.name()).isEqualTo("Alice Wonderland");
        assertThat(user.displayName()).isEqualTo("Alice Wonderland");
        assertThat(user.profileUrl()).isEqualTo("https://www.facebook.com/u100");

        FacebookUser anon = new FacebookUser("", "");
        assertThat(anon.displayName()).isEqualTo("Anonymous");
        assertThat(anon.hasPicture()).isFalse();
        assertThat(anon.profileUrl()).isNull();

        FacebookUser pictured = new FacebookUser("u101", "Bob Builder", null, "https://example.com/bob.jpg");
        assertThat(pictured.pictureUrl()).isEqualTo("https://example.com/bob.jpg");
        assertThat(pictured.hasPicture()).isTrue();
        assertThat(pictured.profileUrl()).isEqualTo("https://www.facebook.com/u101");

        FacebookReaction reaction = new FacebookReaction("u100", "Alice Wonderland", "LOVE");
        assertThat(reaction.type()).isEqualTo("LOVE");
        assertThat(reaction.user().id()).isEqualTo("u100");
    }

    @Test
    void shouldCreateFacebookCommentWithAuthorNestedRepliesAndReactors() {
        Instant now = Instant.now();
        FacebookUser author = new FacebookUser("u1", "John Doe");
        FacebookUser replier = new FacebookUser("u2", "Jane Smith");
        FacebookReaction commentReactor = new FacebookReaction("u3", "Bob", "LIKE");

        FacebookComment reply = new FacebookComment("r1", "I agree!", now, FacebookReactionSummary.empty(), replier, List.of(), List.of());
        FacebookComment comment = new FacebookComment(
                "c1", "Great post!", now, new FacebookReactionSummary(1, 1, 0, 0, 0, 0, 0, 0),
                author, List.of(reply), List.of(commentReactor)
        );

        assertThat(comment.from()).isEqualTo(author);
        assertThat(comment.replies()).hasSize(1);
        assertThat(comment.replies().get(0).from()).isEqualTo(replier);
        assertThat(comment.userReactions()).hasSize(1);
        assertThat(comment.userReactions().get(0).type()).isEqualTo("LIKE");
    }

    @Test
    void shouldCreateFacebookPostAndReviewWithUserDetails() {
        Instant now = Instant.now();
        FacebookReaction postReactor = new FacebookReaction("u4", "Charlie", "CARE");
        FacebookPost post = new FacebookPost("p1", "Hello", now, List.of(), FacebookReactionSummary.empty(), List.of(postReactor));
        assertThat(post.userReactions()).containsExactly(postReactor);

        FacebookUser reviewer = new FacebookUser("u5", "David");
        FacebookReview review = new FacebookReview(now, "positive", "Super friendly", 5, true, reviewer);
        assertThat(review.reviewer()).isEqualTo(reviewer);
    }

    @Test
    void shouldCreateAndVerifyInstagramModels() {
        Instant now = Instant.now();
        InstagramUser user = new InstagramUser("ig_1", "photographer_sam", "Sam", "https://img.com/sam.jpg");
        assertThat(user.id()).isEqualTo("ig_1");
        assertThat(user.username()).isEqualTo("photographer_sam");
        assertThat(user.pictureUrl()).isEqualTo("https://img.com/sam.jpg");
        assertThat(user.hasPicture()).isTrue();

        InstagramComment reply = new InstagramComment("c_r1", "Thanks!", now, user, 2);
        InstagramComment comment = new InstagramComment("c_1", "Stunning shot!", now, user, 10, List.of(reply));
        assertThat(comment.text()).isEqualTo("Stunning shot!");
        assertThat(comment.likeCount()).isEqualTo(10);
        assertThat(comment.replies()).hasSize(1);

        InstagramMedia media = new InstagramMedia(
                "m_1", "Sunset in Bali #travel", "IMAGE", "https://img.com/1.jpg",
                "https://instagram.com/p/1", now, 250, 5, List.of(comment)
        );
        assertThat(media.id()).isEqualTo("m_1");
        assertThat(media.caption()).isEqualTo("Sunset in Bali #travel");
        assertThat(media.likeCount()).isEqualTo(250);
        assertThat(media.commentsCount()).isEqualTo(5);
        assertThat(media.comments()).hasSize(1);

        InstagramMessage message = new InstagramMessage(
                "msg_1", "Is this print available?", now, user, List.of(), List.of()
        );
        InstagramConversation conv = new InstagramConversation(
                "conv_1", now, List.of(user), List.of(message)
        );
        assertThat(conv.id()).isEqualTo("conv_1");
        assertThat(conv.messages()).hasSize(1);
        assertThat(conv.messages().get(0).message()).isEqualTo("Is this print available?");
    }
}
