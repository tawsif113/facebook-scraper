package com.fbscraper.client;

import com.fbscraper.client.fb.FacebookClient;
import com.fbscraper.config.AppConfig;
import com.fbscraper.model.facebook.FacebookConversation;
import com.fbscraper.model.facebook.FacebookPageRatingSummary;
import com.fbscraper.model.facebook.FacebookPost;
import com.fbscraper.model.facebook.FacebookReview;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FacebookClientTest {

    record FakeResponse(int statusCode, String body) implements HttpResponse<String> {
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (key, value) -> true); }
        @Override public URI uri() { return null; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }

    @Test
    void shouldParsePostsCommentsReactionsAndCursor() {
        String json = """
                {
                  "data": [{
                    "id": "post_1",
                    "message": "Single post test",
                    "created_time": "2026-07-01T12:00:00+0000",
                    "reactions": {
                      "data": [
                        {"id": "u_reactor_1", "name": "Sarah Connor", "type": "LOVE"}
                      ],
                      "summary": {"total_count": 9}
                    },
                    "reaction_total": {"summary": {"total_count": 9}},
                    "reaction_like": {"summary": {"total_count": 4}},
                    "reaction_love": {"summary": {"total_count": 2}},
                    "reaction_care": {"summary": {"total_count": 1}},
                    "reaction_haha": {"summary": {"total_count": 0}},
                    "reaction_wow": {"summary": {"total_count": 1}},
                    "reaction_sad": {"summary": {"total_count": 0}},
                    "reaction_angry": {"summary": {"total_count": 1}},
                    "comments": {"data": [{
                      "id": "c_1",
                      "message": "Nice test!",
                      "created_time": "2026-07-01T12:05:00+0000",
                      "from": {
                        "id": "u_commenter_1",
                        "name": "John Smith",
                        "picture": {"data": {"url": "https://example.com/john.jpg"}}
                      },
                      "reactions": {
                        "data": [{"id": "u_reactor_2", "name": "Amy", "type": "LIKE"}],
                        "summary": {"total_count": 3}
                      },
                      "reaction_total": {"summary": {"total_count": 3}},
                      "reaction_like": {"summary": {"total_count": 1}},
                      "reaction_care": {"summary": {"total_count": 2}},
                      "comments": {"data": [{
                        "id": "c_nested_1",
                        "message": "Thanks John!",
                        "created_time": "2026-07-01T12:06:00+0000",
                        "from": {"id": "u_replier_1", "name": "Admin Bob"},
                        "reactions": {
                          "data": [{"id": "u_commenter_1", "name": "John Smith", "type": "CARE"}]
                        }
                      }]}
                    }]}
                  }],
                  "paging": {"next": "https://graph.facebook.com/v26.0/123/feed?after=next"}
                }
                """;

        FacebookClient.FeedPage page = new FacebookClient(config()).parseFeedPage(json);
        FacebookPost post = page.posts().get(0);

        assertThat(post.reactions().total()).isEqualTo(9);
        assertThat(post.reactions().care()).isEqualTo(1);
        assertThat(post.userReactions()).hasSize(1);
        assertThat(post.userReactions().get(0).name()).isEqualTo("Sarah Connor");
        assertThat(post.userReactions().get(0).type()).isEqualTo("LOVE");

        assertThat(post.comments()).hasSize(1);
        var comment = post.comments().get(0);
        assertThat(comment.from().name()).isEqualTo("John Smith");
        assertThat(comment.from().id()).isEqualTo("u_commenter_1");
        assertThat(comment.from().pictureUrl()).isEqualTo("https://example.com/john.jpg");
        assertThat(comment.from().hasPicture()).isTrue();
        assertThat(comment.userReactions()).hasSize(1);
        assertThat(comment.userReactions().get(0).name()).isEqualTo("Amy");

        assertThat(comment.replies()).hasSize(1);
        var reply = comment.replies().get(0);
        assertThat(reply.id()).isEqualTo("c_nested_1");
        assertThat(reply.from().name()).isEqualTo("Admin Bob");
        assertThat(reply.message()).isEqualTo("Thanks John!");
        assertThat(reply.userReactions()).hasSize(1);
        assertThat(reply.userReactions().get(0).type()).isEqualTo("CARE");

        assertThat(page.nextUrl()).contains("after=next");
    }

    @Test
    void shouldBuildEncodedFeedUrlWithLimitsAndReactionFields() {
        FacebookClient client = new FacebookClient(config());

        String decoded = URLDecoder.decode(client.buildFeedUrl(), StandardCharsets.UTF_8);

        assertThat(decoded)
                .contains("/v26.0/123/feed?")
                .contains("limit=100")
                .contains("from{id,name,picture}")
                .contains("comments.limit(75){id,message,created_time,from{id,name,picture},reactions.limit(100){id,name,type}")
                .contains("reactions.type(CARE).limit(0).summary(total_count).as(reaction_care)")
                .contains("reactions.type(ANGRY).limit(0).summary(total_count).as(reaction_angry)")
                .contains("access_token=token");
    }

    @Test
    void shouldParseReviewsWithReviewer() {
        String json = """
                {
                  "data": [{
                    "created_time": "2026-08-01T12:00:00+0000",
                    "recommendation_type": "positive",
                    "review_text": "Great place!",
                    "rating": 5,
                    "has_review": true,
                    "reviewer": {
                      "id": "u_reviewer_1",
                      "name": "Jane Reviewer"
                    }
                  }]
                }
                """;
        FacebookClient.ReviewPage page = new FacebookClient(config()).parseReviewPage(json);
        assertThat(page.reviews()).hasSize(1);
        FacebookReview review = page.reviews().get(0);
        assertThat(review.reviewer().id()).isEqualTo("u_reviewer_1");
        assertThat(review.reviewer().name()).isEqualTo("Jane Reviewer");
    }

    @Test
    void shouldPaginateAcrossFeedPages() {
        Queue<HttpResponse<String>> responses = new LinkedList<>(List.of(
                new FakeResponse(200, feedPageJson("post_1", "https://graph.facebook.com/v26.0/123/feed?after=next")),
                new FakeResponse(200, feedPageJson("post_2", null))
        ));
        FacebookClient client = new FacebookClient(config(), request -> responses.remove());

        List<FacebookPost> posts = client.fetchPageFeed();

        assertThat(posts).hasSize(2);
        assertThat(posts.get(0).id()).isEqualTo("post_1");
        assertThat(posts.get(1).id()).isEqualTo("post_2");
    }

    @Test
    void shouldRespectMaxPageLimit() {
        AppConfig onePage = new AppConfig("123", "token", "v26.0", 100, 75, 1, -0.05);
        FacebookClient client = new FacebookClient(
                onePage,
                request -> new FakeResponse(200, feedPageJson("post_1", "https://graph.facebook.com/next"))
        );

        assertThat(client.fetchPageFeed()).hasSize(1);
    }

    @Test
    void shouldRejectMissingCredentials() {
        AppConfig missing = new AppConfig("", "", "v26.0", 100, 75, 5, -0.05);

        assertThatThrownBy(() -> new FacebookClient(missing).fetchPageFeed())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing required fb.page.id");
    }

    @Test
    void shouldFetchPageRatingSummary() {
        FacebookClient client = new FacebookClient(
                config(),
                request -> new FakeResponse(200, "{\"overall_star_rating\":4.8,\"rating_count\":150}")
        );

        FacebookPageRatingSummary summary = client.fetchPageRatingSummary();

        assertThat(summary.overallStarRating()).isEqualTo(4.8);
        assertThat(summary.ratingCount()).isEqualTo(150);
    }

    @Test
    void shouldFetchReviewsWithPagination() {
        Queue<HttpResponse<String>> responses = new LinkedList<>(List.of(
                new FakeResponse(200, reviewPageJson("Excellent service", "positive", "https://graph.facebook.com/next")),
                new FakeResponse(200, reviewPageJson("Poor service", "negative", null))
        ));
        FacebookClient client = new FacebookClient(config(), request -> responses.remove());

        List<FacebookReview> reviews = client.fetchPageReviews();

        assertThat(reviews).hasSize(2);
        assertThat(reviews.get(0).isPositiveRecommendation()).isTrue();
        assertThat(reviews.get(1).isNegativeRecommendation()).isTrue();
    }

    @Test
    void shouldExplainExpiredAccessTokens() {
        String error = """
                {"error":{"message":"Session has expired","code":190,"error_subcode":463}}
                """;
        FacebookClient client = new FacebookClient(config(), request -> new FakeResponse(401, error));

        assertThatThrownBy(client::fetchPageFeed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired or invalid")
                .hasMessageContaining("Session has expired");
    }

    @Test
    void shouldParseConversationsWithParticipantsAndMessages() {
        String json = """
                {
                  "data": [{
                    "id": "t_100",
                    "updated_time": "2026-07-02T15:00:00+0000",
                    "participants": {
                      "data": [
                        {"id": "u_99", "name": "Alice User", "email": "alice@example.com"},
                        {"id": "123", "name": "My Page"}
                      ]
                    },
                    "messages": {
                      "data": [{
                        "id": "m_1",
                        "message": "Where is my delivery?",
                        "created_time": "2026-07-02T14:55:00+0000",
                        "from": {"id": "u_99", "name": "Alice User"},
                        "to": {"data": [{"id": "123", "name": "My Page"}]},
                        "attachments": {"data": [{
                          "id": "att_1",
                          "mime_type": "image/png",
                          "name": "receipt.png",
                          "image_data": {"url": "https://cdn.example.com/receipt.png", "preview_url": "https://cdn.example.com/preview.png"}
                        }]}
                      }]
                    }
                  }],
                  "paging": {"next": "https://graph.facebook.com/v26.0/123/conversations?after=cursor"}
                }
                """;

        FacebookClient.ConversationPage page = new FacebookClient(config()).parseConversationsPage(json);
        assertThat(page.conversations()).hasSize(1);
        FacebookConversation conv = page.conversations().get(0);
        assertThat(conv.id()).isEqualTo("t_100");
        assertThat(conv.participants()).hasSize(2);
        assertThat(conv.participants().get(0).name()).isEqualTo("Alice User");
        assertThat(conv.messages()).hasSize(1);
        assertThat(conv.messages().get(0).message()).isEqualTo("Where is my delivery?");
        assertThat(conv.messages().get(0).from().id()).isEqualTo("u_99");
        assertThat(conv.messages().get(0).to()).hasSize(1);
        assertThat(conv.messages().get(0).to().get(0).id()).isEqualTo("123");
        assertThat(conv.messages().get(0).attachments()).hasSize(1);
        assertThat(conv.messages().get(0).attachments().get(0).name()).isEqualTo("receipt.png");
        assertThat(conv.messages().get(0).attachments().get(0).isImage()).isTrue();
        assertThat(conv.messages().get(0).attachments().get(0).url()).isEqualTo("https://cdn.example.com/receipt.png");
        assertThat(page.nextUrl()).contains("after=cursor");
    }

    @Test
    void shouldHandleMissingMessagingPermissionsGracefully() {
        String permissionError = """
                {
                  "error": {
                    "message": "(#10) To read conversations, user must grant pages_messaging permission.",
                    "type": "OAuthException",
                    "code": 10
                  }
                }
                """;
        FacebookClient client = new FacebookClient(config(), request -> new FakeResponse(400, permissionError));
        List<FacebookConversation> conversations = client.fetchPageConversations();
        assertThat(conversations).isEmpty();
    }

    private AppConfig config() {
        return new AppConfig("123", "token", "v26.0", 100, 75, 5, -0.05);
    }

    private String feedPageJson(String postId, String nextUrl) {
        String paging = nextUrl == null ? "{}" : "{\"next\":\"" + nextUrl + "\"}";
        return "{\"data\":[{\"id\":\"" + postId
                + "\",\"message\":\"Post\",\"created_time\":\"2026-07-01T10:00:00+0000\","
                + "\"comments\":{\"data\":[]}}],\"paging\":" + paging + "}";
    }

    private String reviewPageJson(String text, String type, String nextUrl) {
        String paging = nextUrl == null ? "{}" : "{\"next\":\"" + nextUrl + "\"}";
        return "{\"data\":[{\"created_time\":\"2026-08-01T12:00:00+0000\","
                + "\"recommendation_type\":\"" + type + "\",\"review_text\":\"" + text
                + "\",\"rating\":5,\"has_review\":true}],\"paging\":" + paging + "}";
    }
}
