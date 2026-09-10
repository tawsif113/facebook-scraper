package com.fbscraper.client;

import com.fbscraper.config.XConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class XUrlBuilderTest {

    private final XConfig config = new XConfig("token", "alice", 10, 100, 100, true, true);

    @Test
    void shouldBuildUserPostsRequestForOriginalPosts() {
        String url = XUrlBuilder.buildUserPostsUrl(config, "42");
        assertTrue(url.startsWith("https://api.x.com/2/users/42/tweets?"));
        assertTrue(url.contains("max_results=10"));
        assertTrue(url.contains("exclude=replies%2Cretweets"));
        assertTrue(url.contains("post.fields="));
    }

    @Test
    void shouldBuildConversationSearchWithAuthorExpansion() {
        String url = XUrlBuilder.buildRepliesUrl(config, "123456789");
        assertTrue(url.startsWith("https://api.x.com/2/tweets/search/recent?"));
        assertTrue(url.contains("query=conversation_id%3A123456789"));
        assertTrue(url.contains("expansions=author_id%2Creferenced_posts"));
        assertTrue(url.contains("user.fields="));
    }

    @Test
    void shouldBuildEngagementIdentityEndpoints() {
        assertTrue(XUrlBuilder.buildLikingUsersUrl(config, "99").contains("/tweets/99/liking_users"));
        assertTrue(XUrlBuilder.buildRepostingUsersUrl(config, "99").contains("/tweets/99/retweeted_by"));
    }
}
