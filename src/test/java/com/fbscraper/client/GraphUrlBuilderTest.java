package com.fbscraper.client;

import com.fbscraper.client.fb.GraphUrlBuilder;
import com.fbscraper.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GraphUrlBuilderTest {

    private final AppConfig config = new AppConfig("my-page-123", "secret-token", "v26.0", 50, 25, 3, -0.05);

    @Test
    void shouldBuildFeedUrlWithEncodedFieldsAndAccessToken() {
        String url = GraphUrlBuilder.buildFeedUrl(config);

        assertThat(url).startsWith("https://graph.facebook.com/v26.0/my-page-123/feed?");
        assertThat(url).contains("access_token=secret-token");
        assertThat(url).contains("limit=50");

        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertThat(decoded).contains("comments.limit(25)");
        assertThat(decoded).contains("comments.limit(100)");
        assertThat(decoded).contains("from{id,name,picture}");
        assertThat(decoded).contains("reaction_like");
    }

    @Test
    void shouldBuildRatingSummaryUrl() {
        String url = GraphUrlBuilder.buildRatingSummaryUrl(config);

        assertThat(url).isEqualTo("https://graph.facebook.com/v26.0/my-page-123?fields=overall_star_rating,rating_count&access_token=secret-token");
    }

    @Test
    void shouldBuildReviewsUrl() {
        String url = GraphUrlBuilder.buildReviewsUrl(config);

        assertThat(url).startsWith("https://graph.facebook.com/v26.0/my-page-123/ratings?");
        assertThat(url).contains("access_token=secret-token");
        assertThat(url).contains("limit=50");
    }

    @Test
    void shouldBuildConversationsUrl() {
        String url = GraphUrlBuilder.buildConversationsUrl(config);

        assertThat(url).startsWith("https://graph.facebook.com/v26.0/my-page-123/conversations?");
        assertThat(url).contains("access_token=secret-token");
        assertThat(url).contains("limit=100");
        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertThat(decoded).contains("messages.limit(100)");
    }

    @Test
    void shouldHandleWithAccessTokenGracefully() {
        assertThat(GraphUrlBuilder.withAccessToken(null, "tok")).isNull();
        assertThat(GraphUrlBuilder.withAccessToken("https://example.com", null)).isEqualTo("https://example.com");
        assertThat(GraphUrlBuilder.withAccessToken("https://example.com", "")).isEqualTo("https://example.com");
        assertThat(GraphUrlBuilder.withAccessToken("https://example.com?access_token=existing", "new")).isEqualTo("https://example.com?access_token=existing");
        assertThat(GraphUrlBuilder.withAccessToken("https://example.com?foo=bar", "tok")).isEqualTo("https://example.com?foo=bar&access_token=tok");
    }
}
