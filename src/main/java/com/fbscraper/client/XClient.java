package com.fbscraper.client;

import com.fbscraper.config.XConfig;
import com.fbscraper.model.x.XPost;
import com.fbscraper.model.x.XReply;
import com.fbscraper.model.x.XUser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Component
public class XClient {

    @FunctionalInterface
    public interface HttpSender {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final XConfig config;
    private final HttpSender httpSender;
    private final XResponseParser parser;

    public XClient(XConfig config) {
        this(config, HttpClient.newHttpClient(), new XResponseParser());
    }

    public XClient(XConfig config, HttpClient httpClient) {
        this(config, request -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), new XResponseParser());
    }

    public XClient(XConfig config, HttpSender httpSender) {
        this(config, httpSender, new XResponseParser());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public XClient(XConfig config, XResponseParser parser) {
        this(config, HttpClient.newHttpClient(), parser);
    }

    public XClient(XConfig config, HttpClient httpClient, XResponseParser parser) {
        this(config, request -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()), parser);
    }

    public XClient(XConfig config, HttpSender httpSender, XResponseParser parser) {
        this.config = config;
        this.httpSender = httpSender;
        this.parser = parser;
    }

    public XUser fetchUser(String usernameOverride) {
        validateToken();
        String username = config.resolveUsername(usernameOverride);
        if (username.isBlank()) {
            throw new IllegalStateException("Enter an X username or configure x.username in application.properties");
        }

        HttpResponse<String> response = sendGet(XUrlBuilder.buildUserLookupUrl(username), Duration.ofSeconds(15));
        ensureSuccess(response, "look up @" + username);

        return parser.parseUser(response.body())
                .orElseThrow(() -> new IllegalStateException("X user @" + username + " was not found"));
    }

    public List<XPost> fetchUserPosts(XUser user) {
        validateToken();
        if (user == null || user.id().isBlank()) {
            throw new IllegalArgumentException("X user id is required");
        }
        if (user.protectedAccount()) {
            throw new IllegalStateException("@" + user.username() + " is protected; public app-only access cannot read this user's posts");
        }

        HttpResponse<String> response = sendGet(XUrlBuilder.buildUserPostsUrl(config, user.id()), Duration.ofSeconds(20));
        ensureSuccess(response, "fetch posts for @" + user.username());
        return parser.parsePostsPage(response.body(), user).posts();
    }

    public List<XPost> searchRecentPosts(String query) {
        validateToken();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Enter an X search query");
        }

        HttpResponse<String> response = sendGet(
                XUrlBuilder.buildRecentSearchUrl(config, query),
                Duration.ofSeconds(20)
        );
        ensureSuccess(response, "search recent posts");
        return parser.parsePostsPage(response.body(), null).posts();
    }

    public List<XReply> fetchReplies(String conversationId) {
        validateToken();
        HttpResponse<String> response = sendGet(XUrlBuilder.buildRepliesUrl(config, conversationId), Duration.ofSeconds(20));
        ensureSuccess(response, "fetch replies for post " + conversationId);
        return parser.parseRepliesPage(response.body()).replies().stream()
                .filter(reply -> !conversationId.equals(reply.id()))
                .toList();
    }

    public List<XUser> fetchLikingUsers(String postId) {
        validateToken();
        HttpResponse<String> response = sendGet(XUrlBuilder.buildLikingUsersUrl(config, postId), Duration.ofSeconds(20));
        ensureSuccess(response, "fetch liking users for post " + postId);
        return parser.parseUsersPage(response.body()).users();
    }

    public List<XUser> fetchRepostingUsers(String postId) {
        validateToken();
        HttpResponse<String> response = sendGet(XUrlBuilder.buildRepostingUsersUrl(config, postId), Duration.ofSeconds(20));
        ensureSuccess(response, "fetch reposting users for post " + postId);
        return parser.parseUsersPage(response.body()).users();
    }

    private HttpResponse<String> sendGet(String url, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + config.bearerToken())
                .header("Accept", "application/json")
                .timeout(timeout)
                .GET()
                .build();
        try {
            return httpSender.send(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("X API request was interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to call X API: " + e.getMessage(), e);
        }
    }

    private void ensureSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }

        String apiMessage = parser.parseError(response.body());
        String prefix = switch (response.statusCode()) {
            case 401 -> "X Bearer Token is invalid or expired";
            case 402 -> "X API credits or endpoint access are unavailable";
            case 403 -> "X API access is forbidden for this resource or endpoint";
            case 404 -> "X resource was not found";
            case 429 -> "X API rate limit exceeded";
            default -> "X API request failed";
        };
        throw new IllegalStateException(prefix + " while trying to " + operation + " [HTTP "
                + response.statusCode() + "]: " + apiMessage);
    }

    private void validateToken() {
        if (!config.hasBearerToken()) {
            throw new IllegalStateException("Missing x.bearer-token in application.properties");
        }
    }
}
