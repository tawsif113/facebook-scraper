package com.fbscraper.client.fb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fbscraper.model.facebook.FacebookAttachment;
import com.fbscraper.model.facebook.FacebookComment;
import com.fbscraper.model.facebook.FacebookConversation;
import com.fbscraper.model.facebook.FacebookMessage;
import com.fbscraper.model.facebook.FacebookPageRatingSummary;
import com.fbscraper.model.facebook.FacebookPost;
import com.fbscraper.model.facebook.FacebookReaction;
import com.fbscraper.model.facebook.FacebookReactionSummary;
import com.fbscraper.model.facebook.FacebookReview;
import com.fbscraper.model.facebook.FacebookUser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Component
public class GraphResponseParser {

    public record ApiErrorInfo(String message, int code, int subcode) {
    }

    private static final DateTimeFormatter FACEBOOK_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final ObjectMapper objectMapper;

    public GraphResponseParser() {
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public GraphResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public FacebookClient.FeedPage parseFeedPage(String json) {
        List<FacebookPost> posts = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode postNode : data) {
                    List<FacebookComment> comments = new ArrayList<>();
                    JsonNode commentData = postNode.path("comments").path("data");
                    if (commentData.isArray()) {
                        for (JsonNode commentNode : commentData) {
                            comments.add(parseComment(commentNode));
                        }
                    }
                    List<FacebookReaction> userReactions = parseUserReactions(postNode.path("reactions").path("data"));

                    posts.add(new FacebookPost(
                            postNode.path("id").asText(""),
                            postNode.path("message").asText(""),
                            parseInstant(postNode.path("created_time").asText("")),
                            comments,
                            parseReactions(postNode),
                            userReactions
                    ));
                }
            }
            return new FacebookClient.FeedPage(posts, pagingNext(root));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Facebook feed JSON", e);
        }
    }

    public FacebookPageRatingSummary parseRatingSummary(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return new FacebookPageRatingSummary(
                    root.path("overall_star_rating").asDouble(0.0),
                    root.path("rating_count").asInt(0)
            );
        } catch (Exception e) {
            return FacebookPageRatingSummary.EMPTY;
        }
    }

    public FacebookClient.ReviewPage parseReviewPage(String json) {
        List<FacebookReview> reviews = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode item : data) {
                    String reviewText = item.path("review_text").asText("");
                    FacebookUser reviewer = parseUser(item.path("reviewer"));
                    reviews.add(new FacebookReview(
                            parseInstant(item.path("created_time").asText("")),
                            item.hasNonNull("recommendation_type")
                                    ? item.path("recommendation_type").asText("")
                                    : null,
                            reviewText,
                            item.path("rating").asInt(0),
                            item.path("has_review").asBoolean(!reviewText.isBlank()),
                            reviewer
                    ));
                }
            }
            return new FacebookClient.ReviewPage(reviews, pagingNext(root));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Facebook reviews JSON", e);
        }
    }

    public FacebookClient.ConversationPage parseConversationsPage(String json) {
        List<FacebookConversation> conversations = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode convNode : data) {
                    List<FacebookUser> participants = parseParticipants(convNode.path("participants").path("data"));
                    List<FacebookMessage> messages = parseMessages(convNode.path("messages").path("data"));
                    conversations.add(new FacebookConversation(
                            convNode.path("id").asText(""),
                            parseInstant(convNode.path("updated_time").asText("")),
                            participants,
                            messages
                    ));
                }
            }
            return new FacebookClient.ConversationPage(conversations, pagingNext(root));
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse Facebook conversations JSON", e);
        }
    }

    public ApiErrorInfo parseApiError(String responseBody) {
        try {
            JsonNode error = objectMapper.readTree(responseBody).path("error");
            return new ApiErrorInfo(
                    error.path("message").asText(""),
                    error.path("code").asInt(-1),
                    error.path("error_subcode").asInt(-1)
            );
        } catch (Exception ignored) {
            return new ApiErrorInfo("", -1, -1);
        }
    }

    private FacebookComment parseComment(JsonNode commentNode) {
        String id = commentNode.path("id").asText("");
        String message = commentNode.path("message").asText("");
        Instant createdTime = parseInstant(commentNode.path("created_time").asText(""));
        FacebookReactionSummary reactions = parseReactions(commentNode);
        FacebookUser from = parseUser(commentNode.path("from"));
        List<FacebookReaction> userReactions = parseUserReactions(commentNode.path("reactions").path("data"));

        List<FacebookComment> replies = new ArrayList<>();
        JsonNode replyData = commentNode.path("comments").path("data");
        if (replyData.isArray()) {
            for (JsonNode replyNode : replyData) {
                replies.add(parseComment(replyNode));
            }
        }

        return new FacebookComment(id, message, createdTime, reactions, from, replies, userReactions);
    }

    private FacebookUser parseUser(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return FacebookUser.ANONYMOUS;
        }
        String id = node.path("id").asText("");
        String name = node.path("name").asText("");
        JsonNode pictureNode = node.path("picture").path("data").path("url");
        String pictureUrl = pictureNode.isMissingNode() || pictureNode.isNull()
                ? null
                : pictureNode.asText(null);
        return new FacebookUser(id, name, null, pictureUrl);
    }

    private List<FacebookReaction> parseUserReactions(JsonNode data) {
        List<FacebookReaction> list = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                String name = node.path("name").asText("");
                String type = node.path("type").asText("LIKE");
                list.add(new FacebookReaction(id, name, type));
            }
        }
        return list;
    }

    private List<FacebookUser> parseParticipants(JsonNode data) {
        List<FacebookUser> list = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode node : data) {
                list.add(new FacebookUser(
                        node.path("id").asText(""),
                        node.path("name").asText(""),
                        node.hasNonNull("email") ? node.path("email").asText("") : null
                ));
            }
        }
        return list;
    }

    private List<FacebookMessage> parseMessages(JsonNode data) {
        List<FacebookMessage> list = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode node : data) {
                FacebookUser from = null;
                if (node.hasNonNull("from")) {
                    JsonNode fromNode = node.get("from");
                    from = new FacebookUser(
                            fromNode.path("id").asText(""),
                            fromNode.path("name").asText(""),
                            fromNode.hasNonNull("email") ? fromNode.path("email").asText("") : null
                    );
                }
                List<FacebookUser> to = parseParticipants(node.path("to").path("data"));
                List<FacebookAttachment> attachments = parseAttachments(node.path("attachments").path("data"));
                list.add(new FacebookMessage(
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

    private List<FacebookAttachment> parseAttachments(JsonNode data) {
        List<FacebookAttachment> list = new ArrayList<>();
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

                list.add(new FacebookAttachment(id, mimeType, name, size, url, previewUrl));
            }
        }
        return list;
    }

    private FacebookReactionSummary parseReactions(JsonNode node) {
        return new FacebookReactionSummary(
                reactionCount(node, "reaction_total", "reactions"),
                reactionCount(node, "reaction_like", "like"),
                reactionCount(node, "reaction_love", "love"),
                reactionCount(node, "reaction_care", "care"),
                reactionCount(node, "reaction_haha", "haha"),
                reactionCount(node, "reaction_wow", "wow"),
                reactionCount(node, "reaction_sad", "sad"),
                reactionCount(node, "reaction_angry", "angry")
        );
    }

    private int reactionCount(JsonNode node, String field, String legacyField) {
        JsonNode count = node.path(field).path("summary").path("total_count");
        if (!count.asText("").isBlank()) {
            return count.asInt(0);
        }
        return node.path(legacyField).path("summary").path("total_count").asInt(0);
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
                return OffsetDateTime.parse(text, FACEBOOK_TIME_FORMATTER).toInstant();
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
