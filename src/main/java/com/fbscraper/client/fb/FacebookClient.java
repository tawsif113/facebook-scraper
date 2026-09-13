package com.fbscraper.client.fb;

import com.fbscraper.config.AppConfig;
import com.fbscraper.model.facebook.FacebookConversation;
import com.fbscraper.model.facebook.FacebookPageRatingSummary;
import com.fbscraper.model.facebook.FacebookPost;
import com.fbscraper.model.facebook.FacebookReview;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class FacebookClient {

    public record FeedPage(List<FacebookPost> posts, String nextUrl) {
    }

    public record ReviewPage(List<FacebookReview> reviews, String nextUrl) {
    }

    public record ConversationPage(List<FacebookConversation> conversations, String nextUrl) {
    }

    @FunctionalInterface
    public interface HttpSender {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final AppConfig config;
    private final HttpSender httpSender;
    private final GraphResponseParser parser;

    public FacebookClient(AppConfig config) {
        this(config, HttpClient.newHttpClient(), new GraphResponseParser());
    }

    public FacebookClient(AppConfig config, HttpClient httpClient) {
        this(config, request -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), new GraphResponseParser());
    }

    public FacebookClient(AppConfig config, HttpSender httpSender) {
        this(config, httpSender, new GraphResponseParser());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FacebookClient(AppConfig config, GraphResponseParser parser) {
        this(config, HttpClient.newHttpClient(), parser);
    }

    public FacebookClient(AppConfig config, HttpClient httpClient, GraphResponseParser parser) {
        this(config, request -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), parser);
    }

    public FacebookClient(AppConfig config, HttpSender httpSender, GraphResponseParser parser) {
        this.config = config;
        this.httpSender = httpSender;
        this.parser = parser;
    }

    public List<FacebookPost> fetchPageFeed() {
        validateCredentials();

        List<FacebookPost> allPosts = new ArrayList<>();
        String currentUrl = buildFeedUrl();
        int pageCount = 0;

        System.out.printf(
                "[FacebookClient] Fetching posts, comments, and reactions (posts/page=%d, comments/post=%d)%n",
                config.feedLimit(),
                config.commentLimit()
        );

        while (hasText(currentUrl)) {
            pageCount++;
            HttpResponse<String> response = sendGet(currentUrl, Duration.ofSeconds(20));
            if (response.statusCode() != 200) {
                throw handleApiError(response.statusCode(), response.body());
            }

            FeedPage page = parseFeedPage(response.body());
            if (page.posts().isEmpty()) {
                break;
            }

            allPosts.addAll(page.posts());
            System.out.printf(
                    "[FacebookClient] Feed page %d returned %d posts (total=%d)%n",
                    pageCount,
                    page.posts().size(),
                    allPosts.size()
            );

            if (reachedPageLimit(pageCount)) {
                break;
            }
            currentUrl = withAccessToken(page.nextUrl());
        }

        return allPosts;
    }

    public FacebookPageRatingSummary fetchPageRatingSummary() {
        if (!credentialsPresent()) {
            return FacebookPageRatingSummary.EMPTY;
        }

        String url = withAccessToken(GraphUrlBuilder.buildRatingSummaryUrl(config));

        try {
            HttpResponse<String> response = sendGet(url, Duration.ofSeconds(15));
            if (response.statusCode() != 200) {
                return FacebookPageRatingSummary.EMPTY;
            }
            return parser.parseRatingSummary(response.body());
        } catch (Exception e) {
            System.err.println("[FacebookClient] Could not fetch Page rating summary: " + e.getMessage());
            return FacebookPageRatingSummary.EMPTY;
        }
    }

    public List<FacebookReview> fetchPageReviews() {
        if (!credentialsPresent()) {
            return List.of();
        }

        List<FacebookReview> allReviews = new ArrayList<>();
        String currentUrl = withAccessToken(GraphUrlBuilder.buildReviewsUrl(config));
        int pageCount = 0;

        while (hasText(currentUrl)) {
            pageCount++;
            try {
                HttpResponse<String> response = sendGet(currentUrl, Duration.ofSeconds(20));
                if (response.statusCode() != 200) {
                    break;
                }

                ReviewPage page = parseReviewPage(response.body());
                if (page.reviews().isEmpty()) {
                    break;
                }
                allReviews.addAll(page.reviews());

                if (reachedPageLimit(pageCount)) {
                    break;
                }
                currentUrl = withAccessToken(page.nextUrl());
            } catch (RuntimeException e) {
                System.err.println("[FacebookClient] Could not fetch Page reviews: " + e.getMessage());
                break;
            }
        }

        return allReviews;
    }

    public List<FacebookConversation> fetchPageConversations() {
        if (!credentialsPresent()) {
            return List.of();
        }

        List<FacebookConversation> allConversations = new ArrayList<>();
        String currentUrl = buildConversationsUrl();
        int pageCount = 0;

        System.out.printf(
                "[FacebookClient] Fetching conversations and inbox messages (conversations/page=%d, messages/thread=%d)%n",
                config.conversationLimit(),
                config.messageLimit()
        );

        while (hasText(currentUrl)) {
            pageCount++;
            try {
                HttpResponse<String> response = sendGet(currentUrl, Duration.ofSeconds(25));
                if (response.statusCode() != 200) {
                    String body = response.body();
                    if (isMessagingPermissionError(body)) {
                        System.err.println("[FacebookClient] Page conversations inaccessible (missing pages_messaging permission on access token). Skipping inbox extraction.");
                        break;
                    }
                    System.err.println("[FacebookClient] Could not fetch Page conversations [HTTP " + response.statusCode() + "]: " + body);
                    break;
                }

                ConversationPage page = parseConversationsPage(response.body());
                if (page.conversations().isEmpty()) {
                    break;
                }
                allConversations.addAll(page.conversations());

                System.out.printf(
                        "[FacebookClient] Conversation page %d returned %d threads (total=%d)%n",
                        pageCount,
                        page.conversations().size(),
                        allConversations.size()
                );

                if (reachedPageLimit(pageCount)) {
                    break;
                }
                currentUrl = withAccessToken(page.nextUrl());
            } catch (RuntimeException e) {
                System.err.println("[FacebookClient] Could not fetch Page conversations: " + e.getMessage());
                break;
            }
        }

        return allConversations;
    }

    public FeedPage parseFeedPage(String json) {
        return parser.parseFeedPage(json);
    }

    public ReviewPage parseReviewPage(String json) {
        return parser.parseReviewPage(json);
    }

    public ConversationPage parseConversationsPage(String json) {
        return parser.parseConversationsPage(json);
    }

    public String buildFeedUrl() {
        return GraphUrlBuilder.buildFeedUrl(config);
    }

    public String buildConversationsUrl() {
        return GraphUrlBuilder.buildConversationsUrl(config);
    }

    private boolean isMessagingPermissionError(String responseBody) {
        if (!hasText(responseBody)) {
            return false;
        }
        String lower = responseBody.toLowerCase();
        return lower.contains("pages_messaging")
                || lower.contains("read_page_mailboxes")
                || (lower.contains("permission") && lower.contains("conversation"));
    }

    private HttpResponse<String> sendGet(String url, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + config.accessToken())
                .timeout(timeout)
                .GET()
                .build();
        try {
            return httpSender.send(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Facebook Graph API request was interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to call Facebook Graph API: " + e.getMessage(), e);
        }
    }

    private RuntimeException handleApiError(int statusCode, String responseBody) {
        GraphResponseParser.ApiErrorInfo error = parser.parseApiError(responseBody);
        String message = error.message();
        int code = error.code();
        int subcode = error.subcode();

        boolean authError = statusCode == 401
                || code == 190
                || subcode == 463
                || subcode == 467
                || message.toLowerCase().contains("access token")
                || message.toLowerCase().contains("session has expired");

        if (authError) {
            return new IllegalStateException(
                    "Facebook Access Token is expired or invalid. Update fb.access.token in config.properties. ("
                            + (message.isBlank() ? responseBody : message) + ")"
            );
        }
        return new RuntimeException("Facebook API error [HTTP " + statusCode + "]: " + responseBody);
    }

    private void validateCredentials() {
        if (!credentialsPresent()) {
            throw new IllegalStateException("Missing required fb.page.id or fb.access.token in config.properties");
        }
    }

    private boolean credentialsPresent() {
        return hasText(config.pageId()) && hasText(config.accessToken());
    }

    private boolean reachedPageLimit(int pageCount) {
        return config.maxPages() > 0 && pageCount >= config.maxPages();
    }

    private String withAccessToken(String url) {
        return GraphUrlBuilder.withAccessToken(url, config.accessToken());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
