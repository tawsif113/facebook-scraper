package com.fbscraper.client;

import com.fbscraper.config.XConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class XUrlBuilder {

    private static final String BASE_URL = "https://api.x.com/2";
    private static final String USER_FIELDS =
            "id,name,username,description,profile_image_url,location,verified,protected,public_metrics";
    private static final String POST_FIELDS =
            "id,text,author_id,created_at,conversation_id,lang,public_metrics";

    private XUrlBuilder() {
    }

    public static String buildUserLookupUrl(String username) {
        return BASE_URL + "/users/by/username/" + encodePathSegment(username)
                + "?user.fields=" + encode(USER_FIELDS);
    }

    public static String buildUserPostsUrl(XConfig config, String userId) {
        return BASE_URL + "/users/" + encodePathSegment(userId) + "/tweets"
                + "?max_results=" + config.postLimit()
                + "&exclude=" + encode("replies,retweets")
                + "&post.fields=" + encode(POST_FIELDS);
    }

    public static String buildRepliesUrl(XConfig config, String conversationId) {
        return BASE_URL + "/tweets/search/recent"
                + "?query=" + encode("conversation_id:" + conversationId)
                + "&max_results=" + Math.max(10, config.replyLimit())
                + "&post.fields=" + encode(POST_FIELDS)
                + "&expansions=" + encode("author_id,referenced_posts")
                + "&user.fields=" + encode(USER_FIELDS);
    }

    public static String buildLikingUsersUrl(XConfig config, String postId) {
        return BASE_URL + "/tweets/" + encodePathSegment(postId) + "/liking_users"
                + "?max_results=" + config.engagementUserLimit()
                + "&user.fields=" + encode(USER_FIELDS);
    }

    public static String buildRepostingUsersUrl(XConfig config, String postId) {
        return BASE_URL + "/tweets/" + encodePathSegment(postId) + "/retweeted_by"
                + "?max_results=" + config.engagementUserLimit()
                + "&user.fields=" + encode(USER_FIELDS);
    }

    static String appendPaginationToken(String url, String token) {
        if (token == null || token.isBlank()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "pagination_token=" + encode(token);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePathSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("X API path value cannot be blank");
        }
        return encode(value.trim()).replace("+", "%20");
    }
}
