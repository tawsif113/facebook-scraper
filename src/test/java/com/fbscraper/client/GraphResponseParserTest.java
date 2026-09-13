package com.fbscraper.client;

import com.fbscraper.client.fb.FacebookClient;
import com.fbscraper.client.fb.GraphResponseParser;
import com.fbscraper.model.facebook.FacebookConversation;
import com.fbscraper.model.facebook.FacebookPageRatingSummary;
import com.fbscraper.model.facebook.FacebookPost;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphResponseParserTest {

    private final GraphResponseParser parser = new GraphResponseParser();

    @Test
    void shouldParseFeedPageSuccessfully() {
        String json = """
                {
                  "data": [{
                    "id": "p_10",
                    "message": "Hello world",
                    "created_time": "2026-07-01T10:00:00+0000",
                    "reactions": {
                      "data": [{"id": "u1", "name": "Alice", "type": "LIKE"}],
                      "summary": {"total_count": 5}
                    },
                    "reaction_total": {"summary": {"total_count": 5}},
                    "reaction_like": {"summary": {"total_count": 5}},
                    "comments": {
                      "data": [{
                        "id": "c_1",
                        "message": "Nice one",
                        "created_time": "2026-07-01T10:05:00+0000",
                        "from": {"id": "u2", "name": "Bob", "picture": {"data": {"url": "https://example.com/bob.jpg"}}}
                      }]
                    }
                  }],
                  "paging": {"next": "https://graph.facebook.com/next-cursor"}
                }
                """;

        FacebookClient.FeedPage page = parser.parseFeedPage(json);
        assertThat(page.posts()).hasSize(1);
        FacebookPost post = page.posts().get(0);
        assertThat(post.id()).isEqualTo("p_10");
        assertThat(post.message()).isEqualTo("Hello world");
        assertThat(post.reactions().total()).isEqualTo(5);
        assertThat(post.userReactions()).hasSize(1);
        assertThat(post.comments()).hasSize(1);
        assertThat(post.comments().get(0).from().name()).isEqualTo("Bob");
        assertThat(post.comments().get(0).from().pictureUrl()).isEqualTo("https://example.com/bob.jpg");
        assertThat(post.comments().get(0).from().hasPicture()).isTrue();
        assertThat(page.nextUrl()).isEqualTo("https://graph.facebook.com/next-cursor");
    }

    @Test
    void shouldParseRatingSummary() {
        String json = "{\"overall_star_rating\":4.7,\"rating_count\":200}";
        FacebookPageRatingSummary summary = parser.parseRatingSummary(json);
        assertThat(summary.overallStarRating()).isEqualTo(4.7);
        assertThat(summary.ratingCount()).isEqualTo(200);

        FacebookPageRatingSummary fallback = parser.parseRatingSummary("invalid json");
        assertThat(fallback).isEqualTo(FacebookPageRatingSummary.EMPTY);
    }

    @Test
    void shouldParseReviewPage() {
        String json = """
                {
                  "data": [{
                    "created_time": "2026-07-01T11:00:00+0000",
                    "recommendation_type": "positive",
                    "review_text": "Awesome product!",
                    "rating": 5,
                    "has_review": true,
                    "reviewer": {"id": "rev_1", "name": "Jane"}
                  }],
                  "paging": {}
                }
                """;

        FacebookClient.ReviewPage page = parser.parseReviewPage(json);
        assertThat(page.reviews()).hasSize(1);
        assertThat(page.reviews().get(0).reviewer().name()).isEqualTo("Jane");
        assertThat(page.reviews().get(0).isPositiveRecommendation()).isTrue();
        assertThat(page.nextUrl()).isNull();
    }

    @Test
    void shouldParseConversationsPage() {
        String json = """
                {
                  "data": [{
                    "id": "t_99",
                    "updated_time": "2026-07-01T12:00:00+0000",
                    "participants": {"data": [{"id": "u1", "name": "Alice"}]},
                    "messages": {
                      "data": [{
                        "id": "m1",
                        "message": "Hey",
                        "created_time": "2026-07-01T12:00:00+0000",
                        "from": {"id": "u1", "name": "Alice"},
                        "to": {"data": [{"id": "page_1", "name": "Page"}]},
                        "attachments": {"data": []}
                      }]
                    }
                  }]
                }
                """;

        FacebookClient.ConversationPage page = parser.parseConversationsPage(json);
        assertThat(page.conversations()).hasSize(1);
        FacebookConversation conv = page.conversations().get(0);
        assertThat(conv.id()).isEqualTo("t_99");
        assertThat(conv.messages()).hasSize(1);
        assertThat(conv.messages().get(0).message()).isEqualTo("Hey");
    }

    @Test
    void shouldParseApiError() {
        String json = "{\"error\":{\"message\":\"Session expired\",\"code\":190,\"error_subcode\":463}}";
        GraphResponseParser.ApiErrorInfo error = parser.parseApiError(json);
        assertThat(error.message()).isEqualTo("Session expired");
        assertThat(error.code()).isEqualTo(190);
        assertThat(error.subcode()).isEqualTo(463);

        GraphResponseParser.ApiErrorInfo fallback = parser.parseApiError("not json");
        assertThat(fallback.message()).isEmpty();
        assertThat(fallback.code()).isEqualTo(-1);
    }

    @Test
    void shouldThrowOnMalformedFeedJson() {
        assertThatThrownBy(() -> parser.parseFeedPage("not-a-json"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse Facebook feed JSON");
    }
}
