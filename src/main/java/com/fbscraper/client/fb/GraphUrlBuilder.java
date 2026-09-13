package com.fbscraper.client.fb;

import com.fbscraper.config.AppConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class GraphUrlBuilder {

    public static final String REACTION_FIELDS =
            "reactions.limit(0).summary(total_count).as(reaction_total),"
                    + "reactions.type(LIKE).limit(0).summary(total_count).as(reaction_like),"
                    + "reactions.type(LOVE).limit(0).summary(total_count).as(reaction_love),"
                    + "reactions.type(CARE).limit(0).summary(total_count).as(reaction_care),"
                    + "reactions.type(HAHA).limit(0).summary(total_count).as(reaction_haha),"
                    + "reactions.type(WOW).limit(0).summary(total_count).as(reaction_wow),"
                    + "reactions.type(SAD).limit(0).summary(total_count).as(reaction_sad),"
                    + "reactions.type(ANGRY).limit(0).summary(total_count).as(reaction_angry)";

    private GraphUrlBuilder() {
    }

    public static String buildFeedUrl(AppConfig config) {
        String nestedCommentsField = String.format(
                "comments.limit(%d){id,message,created_time,from{id,name,picture},reactions.limit(%d){id,name,type},%s}",
                config.nestedCommentLimit(),
                config.reactionLimit(),
                REACTION_FIELDS
        );
        String topLevelCommentsField = String.format(
                "comments.limit(%d){id,message,created_time,from{id,name,picture},reactions.limit(%d){id,name,type},%s,%s}",
                config.commentLimit(),
                config.reactionLimit(),
                REACTION_FIELDS,
                nestedCommentsField
        );
        String fields = String.format(
                "id,message,created_time,permalink_url,reactions.limit(%d){id,name,type},%s,%s",
                config.reactionLimit(),
                REACTION_FIELDS,
                topLevelCommentsField
        );
        String url = String.format(
                "https://graph.facebook.com/%s/%s/feed?fields=%s&limit=%d",
                config.apiVersion(),
                config.pageId(),
                URLEncoder.encode(fields, StandardCharsets.UTF_8),
                config.feedLimit()
        );
        return withAccessToken(url, config.accessToken());
    }

    public static String buildRatingSummaryUrl(AppConfig config) {
        String url = String.format(
                "https://graph.facebook.com/%s/%s?fields=overall_star_rating,rating_count",
                config.apiVersion(),
                config.pageId()
        );
        return withAccessToken(url, config.accessToken());
    }

    public static String buildReviewsUrl(AppConfig config) {
        String fields = "created_time,recommendation_type,review_text,rating,has_review,reviewer{id,name}";
        String url = String.format(
                "https://graph.facebook.com/%s/%s/ratings?fields=%s&limit=%d",
                config.apiVersion(),
                config.pageId(),
                URLEncoder.encode(fields, StandardCharsets.UTF_8),
                config.feedLimit()
        );
        return withAccessToken(url, config.accessToken());
    }

    public static String buildConversationsUrl(AppConfig config) {
        String fields = String.format(
                "id,updated_time,participants,messages.limit(%d){id,message,created_time,from,to,attachments{id,mime_type,name,size,file_url,image_data,video_data}}",
                config.messageLimit()
        );
        String url = String.format(
                "https://graph.facebook.com/%s/%s/conversations?fields=%s&limit=%d",
                config.apiVersion(),
                config.pageId(),
                URLEncoder.encode(fields, StandardCharsets.UTF_8),
                config.conversationLimit()
        );
        return withAccessToken(url, config.accessToken());
    }

    public static String withAccessToken(String url, String accessToken) {
        if (!hasText(url) || url.contains("access_token=") || !hasText(accessToken)) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?")
                + "access_token="
                + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
