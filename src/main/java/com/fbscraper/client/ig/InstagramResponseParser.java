package com.fbscraper.client.ig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fbscraper.client.fb.GraphResponseParser;
import com.fbscraper.model.instagram.InstagramAttachment;
import com.fbscraper.model.instagram.InstagramComment;
import com.fbscraper.model.instagram.InstagramConversation;
import com.fbscraper.model.instagram.InstagramMedia;
import com.fbscraper.model.instagram.InstagramMessage;
import com.fbscraper.model.instagram.InstagramUser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class InstagramResponseParser {

    public record BusinessAccountInfo(String id, String username, String name) {
    }

    public record MediaPage(List<InstagramMedia> media, String nextUrl) {
    }

    public record ConversationPage(List<InstagramConversation> conversations, String nextUrl) {
    }

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final ObjectMapper objectMapper;

    public InstagramResponseParser() {
        this(new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    public InstagramResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<BusinessAccountInfo> parseBusinessAccount(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode account = root.path("instagram_business_account");
            if (account.isMissingNode() || account.isNull()) {
                return Optional.empty();
            }
            String id = account.path("id").asText("");
            if (id.isBlank()) {
                return Optional.empty();
            }
            String username = account.path("username").asText("");
            String name = account.path("name").asText(username);
            return Optional.of(new BusinessAccountInfo(id, username, name));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public MediaPage parseMediaPage(String json) {
        List<InstagramMedia> media = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode mediaNode : data) {
                    List<InstagramComment> comments = new ArrayList<>();
                    JsonNode commentData = mediaNode.path("comments").path("data");
                    if (commentData.isArray()) {
                        for (JsonNode commentNode : commentData) {
                            comments.add(parseComment(commentNode));
                        }
                    }

                    int likeCount = mediaNode.path("like_count").asInt(0);
                    int commentsCount = mediaNode.path("comments_count").asInt(comments.size());

                    media.add(new InstagramMedia(
                            mediaNode.path("id").asText(""),
                            mediaNode.path("caption").asText(""),
                            mediaNode.path("media_type").asText("IMAGE"),
                            mediaNode.hasNonNull("media_url") ? mediaNode.path("media_url").asText() : null,
                            mediaNode.hasNonNull("permalink") ? mediaNode.path("permalink").asText() : null,
                            parseInstant(mediaNode.path("timestamp").asText("")),
                            likeCount,
                            commentsCount,
                            comments
                    ));
                }
            }
            return new MediaPage(media, pagingNext(root));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Instagram media JSON", e);
        }
    }

    public ConversationPage parseConversationsPage(String json) {
        List<InstagramConversation> conversations = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode convNode : data) {
                    List<InstagramUser> participants = parseParticipants(convNode.path("participants").path("data"));
                    List<InstagramMessage> messages = parseMessages(convNode.path("messages").path("data"));
                    conversations.add(new InstagramConversation(
                            convNode.path("id").asText(""),
                            parseInstant(convNode.path("updated_time").asText("")),
                            participants,
                            messages
                    ));
                }
            }
            return new ConversationPage(conversations, pagingNext(root));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Instagram conversations JSON", e);
        }
    }

    public GraphResponseParser.ApiErrorInfo parseApiError(String responseBody) {
        try {
            JsonNode error = objectMapper.readTree(responseBody).path("error");
            return new GraphResponseParser.ApiErrorInfo(
                    error.path("message").asText(""),
                    error.path("code").asInt(-1),
                    error.path("error_subcode").asInt(-1)
            );
        } catch (Exception ignored) {
            return new GraphResponseParser.ApiErrorInfo("", -1, -1);
        }
    }

    private InstagramComment parseComment(JsonNode commentNode) {
        String id = commentNode.path("id").asText("");
        String text = commentNode.path("text").asText("");
        Instant timestamp = parseInstant(commentNode.path("timestamp").asText(""));
        int likes = commentNode.path("like_count").asInt(0);

        String username = commentNode.path("username").asText("");
        String userId = commentNode.hasNonNull("from") ? commentNode.path("from").path("id").asText(username) : username;
        InstagramUser from = new InstagramUser(userId, username.isBlank() ? "Instagram User" : username);

        List<InstagramComment> replies = new ArrayList<>();
        JsonNode replyData = commentNode.path("replies").path("data");
        if (replyData.isArray()) {
            for (JsonNode replyNode : replyData) {
                replies.add(parseComment(replyNode));
            }
        }

        return new InstagramComment(id, text, timestamp, from, likes, replies);
    }

    private List<InstagramUser> parseParticipants(JsonNode data) {
        List<InstagramUser> list = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                String username = node.path("username").asText("");
                String name = node.hasNonNull("name")
                        ? node.path("name").asText("")
                        : username;
                list.add(new InstagramUser(
                        id,
                        username.isBlank() ? id : username,
                        name.isBlank() ? id : name
                ));
            }
        }
        return list;
    }

    private List<InstagramMessage> parseMessages(JsonNode data) {
        List<InstagramMessage> list = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode node : data) {
                InstagramUser from = null;
                if (node.hasNonNull("from")) {
                    JsonNode fromNode = node.get("from");
                    String fromId = fromNode.path("id").asText("");
                    String fromUsername = fromNode.path("username").asText("");
                    String fromName = fromNode.hasNonNull("name")
                            ? fromNode.path("name").asText("")
                            : fromUsername;
                    from = new InstagramUser(
                            fromId,
                            fromUsername.isBlank() ? fromId : fromUsername,
                            fromName.isBlank() ? fromId : fromName
                    );
                }
                List<InstagramUser> to = parseParticipants(node.path("to").path("data"));
                List<InstagramAttachment> attachments = parseAttachments(node.path("attachments").path("data"));
                list.add(new InstagramMessage(
                        node.path("id").asText(""),
                        node.path("message").asText(""),
                        parseInstant(node.path("created_time").asText("")),
                        from,
                        to,
                        attachments
                ));
            }
        }
        return list;
    }

    private List<InstagramAttachment> parseAttachments(JsonNode data) {
        List<InstagramAttachment> list = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                String mimeType = node.hasNonNull("mime_type") ? node.path("mime_type").asText() : null;
                String name = node.hasNonNull("name") ? node.path("name").asText() : null;
                Long size = node.hasNonNull("size") ? node.path("size").asLong() : null;

                String url = null;
                String previewUrl = null;

                if (node.hasNonNull("file_url")) {
                    url = node.path("file_url").asText();
                }

                if (node.hasNonNull("image_data")) {
                    JsonNode imageData = node.get("image_data");
                    if (url == null && imageData.hasNonNull("url")) {
                        url = imageData.path("url").asText();
                    }
                    if (imageData.hasNonNull("preview_url")) {
                        previewUrl = imageData.path("preview_url").asText();
                    }
                }

                if (node.hasNonNull("video_data")) {
                    JsonNode videoData = node.get("video_data");
                    if (url == null && videoData.hasNonNull("url")) {
                        url = videoData.path("url").asText();
                    }
                    if (videoData.hasNonNull("preview_url")) {
                        previewUrl = videoData.path("preview_url").asText();
                    }
                }

                list.add(new InstagramAttachment(id, mimeType, name, size, url, previewUrl));
            }
        }
        return list;
    }

    private String pagingNext(JsonNode root) {
        JsonNode next = root.path("paging").path("next");
        return next.isMissingNode() || next.isNull() || next.asText("").isBlank()
                ? null
                : next.asText();
    }

    private Instant parseInstant(String text) {
        if (!hasText(text)) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(text, ISO_FORMATTER).toInstant();
            } catch (DateTimeParseException ignored) {
                try {
                    return Instant.parse(text);
                } catch (DateTimeParseException invalidTimestamp) {
                    return Instant.now();
                }
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
