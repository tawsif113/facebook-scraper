package com.fbscraper.client;

import com.fbscraper.client.ig.InstagramUrlBuilder;
import com.fbscraper.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class InstagramUrlBuilderTest {

    private final AppConfig config = new AppConfig("fb-page-123", "secret-token", "v26.0", 50, 25, 3, -0.05);

    @Test
    void shouldBuildAccountDiscoveryUrl() {
        String url = InstagramUrlBuilder.buildAccountDiscoveryUrl(config);

        assertThat(url).startsWith("https://graph.facebook.com/v26.0/fb-page-123?fields=");
        assertThat(url).contains("access_token=secret-token");
        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertThat(decoded).contains("fields=instagram_business_account{id,username,name}");
    }

    @Test
    void shouldBuildMediaUrlWithNestedCommentsAndReplies() {
        String url = InstagramUrlBuilder.buildMediaUrl(config, "17841400000000000");

        assertThat(url).startsWith("https://graph.facebook.com/v26.0/17841400000000000/media?");
        assertThat(url).contains("access_token=secret-token");
        assertThat(url).contains("limit=50");

        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertThat(decoded).contains("caption");
        assertThat(decoded).contains("like_count");
        assertThat(decoded).contains("comments.limit(25)");
        assertThat(decoded).contains("replies.limit(100)");
    }

    @Test
    void shouldBuildConversationsUrlForInstagramPlatform() {
        String url = InstagramUrlBuilder.buildConversationsUrl(config, "17841400000000000");

        assertThat(url).startsWith("https://graph.facebook.com/v26.0/17841400000000000/conversations?");
        assertThat(url).contains("platform=instagram");
        assertThat(url).contains("access_token=secret-token");
        assertThat(url).contains("limit=100");

        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertThat(decoded).contains("messages.limit(100)");
        assertThat(decoded).contains("participants");
    }

    @Test
    void shouldBuildPageConversationsWithIgUrl() {
        String url = InstagramUrlBuilder.buildPageConversationsWithIgUrl(config);

        assertThat(url).startsWith("https://graph.facebook.com/v26.0/fb-page-123/conversations?");
        assertThat(url).contains("platform=instagram");
        assertThat(url).contains("access_token=secret-token");

        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        assertThat(decoded).contains("messages.limit(100)");
        assertThat(decoded).contains("participants");
    }

    @Test
    void shouldHandleWithAccessTokenGracefully() {
        assertThat(InstagramUrlBuilder.withAccessToken(null, "tok")).isNull();
        assertThat(InstagramUrlBuilder.withAccessToken("https://example.com", null)).isEqualTo("https://example.com");
        assertThat(InstagramUrlBuilder.withAccessToken("https://example.com", "")).isEqualTo("https://example.com");
        assertThat(InstagramUrlBuilder.withAccessToken("https://example.com?access_token=existing", "new")).isEqualTo("https://example.com?access_token=existing");
        assertThat(InstagramUrlBuilder.withAccessToken("https://example.com?foo=bar", "tok")).isEqualTo("https://example.com?foo=bar&access_token=tok");
    }
}
