package com.fbscraper.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fbscraper.model.x.XPost;
import com.fbscraper.model.x.XPublicMetrics;
import com.fbscraper.model.x.XReply;
import com.fbscraper.model.x.XUser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class XResponseParser {

    public record PostPage(List<XPost> posts, String nextToken) {
        public PostPage {
            posts = posts == null ? List.of() : List.copyOf(posts);
        }
    }

    public record ReplyPage(List<XReply> replies, String nextToken) {
        public ReplyPage {
            replies = replies == null ? List.of() : List.copyOf(replies);
        }
    }

    public record UserPage(List<XUser> users, String nextToken) {
        public UserPage {
            users = users == null ? List.of() : List.copyOf(users);
        }
    }

    private final ObjectMapper objectMapper;

    public XResponseParser() {
        this(new ObjectMapper());
    }

    public XResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<XUser> parseUser(String json) {
        try {
            JsonNode data = objectMapper.readTree(json).path("data");
            if (!data.isObject() || data.path("id").asText("").isBlank()) {
                return Optional.empty();
            }
            return Optional.of(parseUserNode(data));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse X user response", e);
        }
    }

    public PostPage parsePostsPage(String json, XUser defaultAuthor) {
        try {
            JsonNode root = objectMapper.readTree(json);
            Map<String, XUser> includedUsers = parseIncludedUsers(root);
            List<XPost> posts = new ArrayList<>();
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    String authorId = node.path("author_id").asText(defaultAuthor == null ? "" : defaultAuthor.id());
                    XUser author = includedUsers.getOrDefault(
                            authorId,
                            defaultAuthor == null ? XUser.minimal(authorId) : defaultAuthor
                    );
                    posts.add(new XPost(
                            node.path("id").asText(""),
                            node.path("text").asText(""),
                            author,
                            parseInstant(node.path("created_at").asText("")),
                            node.path("conversation_id").asText(node.path("id").asText("")),
                            node.path("lang").asText(""),
                            parseMetrics(node.path("public_metrics"))
                    ));
                }
            }
            return new PostPage(posts, nextToken(root));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse X posts response", e);
        }
    }

    public ReplyPage parseRepliesPage(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            Map<String, XUser> users = parseIncludedUsers(root);
            List<XReply> replies = new ArrayList<>();
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    String id = node.path("id").asText("");
                    String authorId = node.path("author_id").asText("");
                    String conversationId = node.path("conversation_id").asText("");
                    String parentPostId = findParentPostId(node);
                    replies.add(new XReply(
                            id,
                            node.path("text").asText(""),
                            users.getOrDefault(authorId, XUser.minimal(authorId)),
                            parseInstant(node.path("created_at").asText("")),
                            conversationId,
                            parentPostId,
                            parseMetrics(node.path("public_metrics"))
                    ));
                }
            }
            return new ReplyPage(replies, nextToken(root));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse X replies response", e);
        }
    }

    public UserPage parseUsersPage(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<XUser> users = new ArrayList<>();
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    users.add(parseUserNode(node));
                }
            }
            return new UserPage(users, nextToken(root));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse X users response", e);
        }
    }

    public String parseError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "Unknown X API error";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String detail = root.path("detail").asText("");
            if (!detail.isBlank()) {
                return detail;
            }
            String title = root.path("title").asText("");
            if (!title.isBlank()) {
                return title;
            }
            JsonNode errors = root.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                JsonNode first = errors.get(0);
                detail = first.path("detail").asText("");
                title = first.path("title").asText("");
                if (!detail.isBlank()) return detail;
                if (!title.isBlank()) return title;
            }
            return responseBody;
        } catch (Exception ignored) {
            return responseBody;
        }
    }

    private Map<String, XUser> parseIncludedUsers(JsonNode root) {
        Map<String, XUser> users = new HashMap<>();
        JsonNode data = root.path("includes").path("users");
        if (data.isArray()) {
            for (JsonNode node : data) {
                XUser user = parseUserNode(node);
                users.put(user.id(), user);
            }
        }
        return users;
    }

    private XUser parseUserNode(JsonNode node) {
        JsonNode metrics = node.path("public_metrics");
        int postCount = metrics.has("post_count")
                ? metrics.path("post_count").asInt(0)
                : metrics.path("tweet_count").asInt(0);
        return new XUser(
                node.path("id").asText(""),
                node.path("name").asText(""),
                node.path("username").asText(""),
                node.path("description").asText(""),
                node.hasNonNull("profile_image_url") ? node.path("profile_image_url").asText() : null,
                node.path("location").asText(""),
                node.path("verified").asBoolean(false),
                node.path("protected").asBoolean(false),
                metrics.path("followers_count").asInt(0),
                metrics.path("following_count").asInt(0),
                postCount
        );
    }

    private XPublicMetrics parseMetrics(JsonNode metrics) {
        if (metrics == null || !metrics.isObject()) {
            return XPublicMetrics.EMPTY;
        }
        int repostCount = metrics.has("repost_count")
                ? metrics.path("repost_count").asInt(0)
                : metrics.path("retweet_count").asInt(0);
        return new XPublicMetrics(
                metrics.path("like_count").asInt(0),
                metrics.path("reply_count").asInt(0),
                repostCount,
                metrics.path("quote_count").asInt(0),
                metrics.path("impression_count").asInt(0)
        );
    }

    private String findParentPostId(JsonNode node) {
        String parent = findParentPostIdInReferences(node.path("referenced_posts"));
        if (!parent.isBlank()) {
            return parent;
        }
        return findParentPostIdInReferences(node.path("referenced_tweets"));
    }

    private String findParentPostIdInReferences(JsonNode references) {
        if (!references.isArray()) {
            return "";
        }
        for (JsonNode reference : references) {
            String type = reference.path("type").asText("");
            if ("replied_to".equalsIgnoreCase(type)) {
                return reference.path("id").asText("");
            }
        }
        return "";
    }

    private String nextToken(JsonNode root) {
        JsonNode meta = root.path("meta");
        String token = meta.path("next_token").asText("");
        if (token.isBlank()) {
            token = meta.path("pagination_token").asText("");
        }
        return token.isBlank() ? null : token;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }
}
