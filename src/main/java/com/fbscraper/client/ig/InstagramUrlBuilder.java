package com.fbscraper.client.ig;

import com.fbscraper.config.AppConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class InstagramUrlBuilder {

    private InstagramUrlBuilder() {
    }

    public static String buildAccountDiscoveryUrl(AppConfig config) {
        String url = String.format(
                "https://graph.facebook.com/%s/%s?fields=%s",
                config.apiVersion(),
                config.pageId(),
                URLEncoder.encode("instagram_business_account{id,username,name}", StandardCharsets.UTF_8)
        );
        return withAccessToken(url, config.accessToken());
    }

    public static String buildMediaUrl(AppConfig config, String igUserId) {
        String repliesField = String.format(
                "replies.limit(%d){id,text,timestamp,username,like_count}",
                config.nestedCommentLimit()
        );
        String commentsField = String.format(
                "comments.limit(%d){id,text,timestamp,username,like_count,%s}",
                config.commentLimit(),
                repliesField
        );
        String fields = String.format(
                "id,caption,media_type,media_url,permalink,timestamp,like_count,comments_count,%s",
                commentsField
        );
        String url = String.format(
                "https://graph.facebook.com/%s/%s/media?fields=%s&limit=%d",
                config.apiVersion(),
                igUserId,
                URLEncoder.encode(fields, StandardCharsets.UTF_8),
                config.feedLimit()
        );
        return withAccessToken(url, config.accessToken());
    }

    public static String buildConversationsUrl(AppConfig config, String igUserId) {
        String fields = String.format(
                "id,updated_time,participants,messages.limit(%d){id,message,created_time,from,to,attachments{id,mime_type,name,size,file_url,image_data,video_data}}",
                config.messageLimit()
        );
        String url = String.format(
                "https://graph.facebook.com/%s/%s/conversations?platform=instagram&fields=%s&limit=%d",
                config.apiVersion(),
                igUserId,
                URLEncoder.encode(fields, StandardCharsets.UTF_8),
                config.conversationLimit()
        );
        return withAccessToken(url, config.accessToken());
    }

    public static String buildPageConversationsWithIgUrl(AppConfig config) {
        String fields = String.format(
                "id,updated_time,participants,messages.limit(%d){id,message,created_time,from,to,attachments{id,mime_type,name,size,file_url,image_data,video_data}}",
                config.messageLimit()
        );
        String url = String.format(
                "https://graph.facebook.com/%s/%s/conversations?platform=instagram&fields=%s&limit=%d",
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
