package com.fbscraper.client.ig;

import com.fbscraper.client.fb.FacebookClient;
import com.fbscraper.client.fb.GraphResponseParser;
import com.fbscraper.config.AppConfig;
import com.fbscraper.model.instagram.InstagramConversation;
import com.fbscraper.model.instagram.InstagramMedia;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class InstagramClient {

    private final AppConfig config;
    private final FacebookClient.HttpSender httpSender;
    private final InstagramResponseParser parser;
    private final InstagramProfileResolver profileResolver;
    private volatile String cachedBusinessAccountId;

    public InstagramClient(AppConfig config) {
        this(config, HttpClient.newHttpClient(), new InstagramResponseParser(), new InstagramProfileResolver());
    }

    public InstagramClient(AppConfig config, HttpClient httpClient) {
        this(config, request -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), new InstagramResponseParser(), new InstagramProfileResolver());
    }

    public InstagramClient(AppConfig config, FacebookClient.HttpSender httpSender) {
        this(config, httpSender, new InstagramResponseParser(), new InstagramProfileResolver());
    }

    public InstagramClient(AppConfig config, InstagramResponseParser parser) {
        this(config, HttpClient.newHttpClient(), parser, new InstagramProfileResolver());
    }

    public InstagramClient(AppConfig config, HttpClient httpClient, InstagramResponseParser parser) {
        this(config, request -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), parser, new InstagramProfileResolver());
    }

    public InstagramClient(AppConfig config, FacebookClient.HttpSender httpSender, InstagramResponseParser parser) {
        this(config, httpSender, parser, new InstagramProfileResolver());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InstagramClient(AppConfig config, InstagramResponseParser parser, InstagramProfileResolver profileResolver) {
        this(config, HttpClient.newHttpClient(), parser, profileResolver);
    }

    public InstagramClient(AppConfig config, HttpClient httpClient, InstagramResponseParser parser, InstagramProfileResolver profileResolver) {
        this(config, request -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), parser, profileResolver);
    }

    public InstagramClient(AppConfig config, FacebookClient.HttpSender httpSender, InstagramResponseParser parser, InstagramProfileResolver profileResolver) {
        this.config = config;
        this.httpSender = httpSender;
        this.parser = parser;
        this.profileResolver = profileResolver != null ? profileResolver : new InstagramProfileResolver();
    }

    public String resolveBusinessAccountId() {
        if (hasText(cachedBusinessAccountId)) {
            return cachedBusinessAccountId;
        }

        if (config.hasInstagramAccountId()) {
            this.cachedBusinessAccountId = config.igAccountId();
            return cachedBusinessAccountId;
        }

        validateCredentials();

        String url = InstagramUrlBuilder.buildAccountDiscoveryUrl(config);
        System.out.printf("[InstagramClient] Discovering linked Instagram account for Page %s...%n", config.pageId());

        HttpResponse<String> response = sendGet(url, Duration.ofSeconds(15));
        if (response.statusCode() != 200) {
            throw handleApiError(response.statusCode(), response.body());
        }

        Optional<InstagramResponseParser.BusinessAccountInfo> info = parser.parseBusinessAccount(response.body());
        if (info.isEmpty() || !hasText(info.get().id())) {
            throw new IllegalStateException(
                    "No Instagram Business/Professional Account linked to Facebook Page ID " + config.pageId() + ". "
                            + "Please convert your Instagram account to a Professional account and connect it to your Page, "
                            + "or explicitly configure fb.ig-account-id in application.properties."
            );
        }

        this.cachedBusinessAccountId = info.get().id();
        System.out.printf(
                "[InstagramClient] Discovered Instagram Business Account: @%s (id=%s)%n",
                info.get().username(),
                this.cachedBusinessAccountId
        );
        return this.cachedBusinessAccountId;
    }

    public List<InstagramMedia> fetchMedia() {
        String igUserId = resolveBusinessAccountId();

        List<InstagramMedia> allMedia = new ArrayList<>();
        String currentUrl = InstagramUrlBuilder.buildMediaUrl(config, igUserId);
        int pageCount = 0;

        System.out.printf(
                "[InstagramClient] Fetching Instagram media and comments (media/page=%d, comments/post=%d)%n",
                config.feedLimit(),
                config.commentLimit()
        );

        while (hasText(currentUrl)) {
            pageCount++;
            HttpResponse<String> response = sendGet(currentUrl, Duration.ofSeconds(20));
            if (response.statusCode() != 200) {
                throw handleApiError(response.statusCode(), response.body());
            }

            InstagramResponseParser.MediaPage page = parser.parseMediaPage(response.body());
            if (page.media().isEmpty()) {
                break;
            }

            allMedia.addAll(page.media());
            System.out.printf(
                    "[InstagramClient] Media page %d returned %d items (total=%d)%n",
                    pageCount,
                    page.media().size(),
                    allMedia.size()
            );

            if (reachedPageLimit(pageCount)) {
                break;
            }
            currentUrl = withAccessToken(page.nextUrl());
        }

        if (allMedia.isEmpty()) {
            return allMedia;
        }

        System.out.printf("[InstagramClient] Resolving commenter public profile details for %d media items...%n", allMedia.size());
        return profileResolver.resolveMediaList(allMedia);
    }

    public List<InstagramConversation> fetchConversations() {
        if (!credentialsPresent()) {
            return List.of();
        }

        String igUserId;
        try {
            igUserId = resolveBusinessAccountId();
        } catch (Exception e) {
            System.err.println("[InstagramClient] Skipping Instagram conversations: " + e.getMessage());
            return List.of();
        }

        List<InstagramConversation> allConversations = new ArrayList<>();
        String currentUrl = InstagramUrlBuilder.buildConversationsUrl(config, igUserId);
        int pageCount = 0;
        boolean triedFallback = false;

        System.out.printf(
                "[InstagramClient] Fetching Instagram direct conversations (conversations/page=%d, messages/thread=%d)%n",
                config.conversationLimit(),
                config.messageLimit()
        );

        while (hasText(currentUrl)) {
            pageCount++;
            try {
                HttpResponse<String> response = sendGet(currentUrl, Duration.ofSeconds(25));
                if (response.statusCode() != 200) {
                    String body = response.body();

                    // If primary endpoint fails on first attempt, try the alternative Page endpoint with platform=instagram
                    if (pageCount == 1 && !triedFallback) {
                        System.out.println("[InstagramClient] Direct conversation endpoint failed [HTTP " + response.statusCode() + "]; attempting Page conversations with platform=instagram fallback...");
                        triedFallback = true;
                        pageCount = 0;
                        currentUrl = InstagramUrlBuilder.buildPageConversationsWithIgUrl(config);
                        continue;
                    }

                    if (isMessagingPermissionError(body)) {
                        logMessagingDiagnostics(body);
                        break;
                    }
                    System.err.println("[InstagramClient] Could not fetch Instagram conversations [HTTP " + response.statusCode() + "]: " + body);
                    break;
                }

                InstagramResponseParser.ConversationPage page = parser.parseConversationsPage(response.body());
                if (page.conversations().isEmpty()) {
                    break;
                }
                allConversations.addAll(page.conversations());

                System.out.printf(
                        "[InstagramClient] Conversation page %d returned %d threads (total=%d)%n",
                        pageCount,
                        page.conversations().size(),
                        allConversations.size()
                );

                if (reachedPageLimit(pageCount)) {
                    break;
                }
                currentUrl = withAccessToken(page.nextUrl());
            } catch (RuntimeException e) {
                System.err.println("[InstagramClient] Could not fetch Instagram conversations: " + e.getMessage());
                break;
            }
        }

        return allConversations;
    }

    private void logMessagingDiagnostics(String responseBody) {
        GraphResponseParser.ApiErrorInfo error = parser.parseApiError(responseBody);
        String msg = error.message().isBlank() ? responseBody : error.message();
        System.err.printf("[InstagramClient] Instagram direct messages inaccessible (Code %d: %s)%n", error.code(), msg);
        System.err.println("[InstagramClient] ─────────────────────────────────────────────────────────────────");
        System.err.println("[InstagramClient] ACTION REQUIRED to enable Instagram Direct Messages (DMs):");
        System.err.println("[InstagramClient] 1. Enable in Instagram App (Crucial):");
        System.err.println("[InstagramClient]    Open the Instagram app on your mobile device, go to:");
        System.err.println("[InstagramClient]    Settings and privacy -> Messages and story replies -> Message controls");
        System.err.println("[InstagramClient]    Under 'Connected tools', toggle 'Allow access to messages' to ON.");
        System.err.println("[InstagramClient] 2. Development Mode Roles (Meta App Dashboard):");
        System.err.println("[InstagramClient]    If your Meta App is in Development mode, add your Instagram account under:");
        System.err.println("[InstagramClient]    Meta App Dashboard -> App Roles -> Roles -> 'Instagram Testers',");
        System.err.println("[InstagramClient]    then accept the invite in Instagram (Settings -> Apps and websites -> Tester invites).");
        System.err.println("[InstagramClient] 3. Token Permissions:");
        System.err.println("[InstagramClient]    Ensure the Page Access Token includes 'instagram_manage_messages'.");
        System.err.println("[InstagramClient] ─────────────────────────────────────────────────────────────────");
    }

    private boolean isUnsupportedGetRequest(String responseBody) {
        if (!hasText(responseBody)) {
            return false;
        }
        String lower = responseBody.toLowerCase();
        return lower.contains("unsupported get request")
                || ((lower.contains("\"code\":100") || lower.contains("\"code\": 100"))
                && (lower.contains("error_subcode\":33") || lower.contains("error_subcode\": 33")));
    }

    private boolean isMessagingPermissionError(String responseBody) {
        if (!hasText(responseBody)) {
            return false;
        }
        String lower = responseBody.toLowerCase();
        return lower.contains("instagram_manage_messages")
                || lower.contains("pages_manage_metadata")
                || lower.contains("pages_messaging")
                || lower.contains("permission")
                || lower.contains("access denied")
                || lower.contains("capability")
                || lower.contains("\"code\":3")
                || lower.contains("\"code\": 3");
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
            throw new RuntimeException("Instagram Graph API request was interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to call Instagram Graph API: " + e.getMessage(), e);
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
                    "Meta Access Token is expired or invalid. Update fb.access-token in application.properties. ("
                            + (message.isBlank() ? responseBody : message) + ")"
            );
        }
        return new RuntimeException("Instagram API error [HTTP " + statusCode + "]: " + responseBody);
    }

    private void validateCredentials() {
        if (!credentialsPresent()) {
            throw new IllegalStateException("Missing required fb.page-id or fb.access-token in application.properties");
        }
    }

    private boolean credentialsPresent() {
        return hasText(config.pageId()) && hasText(config.accessToken());
    }

    private boolean reachedPageLimit(int pageCount) {
        return config.maxPages() > 0 && pageCount >= config.maxPages();
    }

    private String withAccessToken(String url) {
        return InstagramUrlBuilder.withAccessToken(url, config.accessToken());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
