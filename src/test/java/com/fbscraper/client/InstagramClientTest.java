package com.fbscraper.client;

import com.fbscraper.client.ig.InstagramClient;
import com.fbscraper.client.ig.InstagramProfileResolver;
import com.fbscraper.client.ig.InstagramResponseParser;
import com.fbscraper.config.AppConfig;
import com.fbscraper.model.instagram.InstagramConversation;
import com.fbscraper.model.instagram.InstagramMedia;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstagramClientTest {

    record FakeResponse(int statusCode, String body) implements HttpResponse<String> {
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (key, value) -> true); }
        @Override public URI uri() { return null; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }

    @Test
    void shouldDiscoverBusinessAccountIdWhenNotExplicitlyConfigured() {
        AppConfig config = new AppConfig("fb_page_123", "tok_valid", "v26.0", 10, 10, 2, -0.05);
        String discoveryJson = """
                {
                  "instagram_business_account": {
                    "id": "178414000123",
                    "username": "my_biz"
                  },
                  "id": "fb_page_123"
                }
                """;

        InstagramClient client = new InstagramClient(config, request -> new FakeResponse(200, discoveryJson));

        String id = client.resolveBusinessAccountId();
        assertThat(id).isEqualTo("178414000123");
    }

    @Test
    void shouldUseExplicitIgAccountIdIfConfigured() {
        AppConfig config = new AppConfig("fb_page_123", "tok_valid", "v26.0", 10, 10, 10, 10, 10, 10, 2, -0.05, "custom_ig_id");

        // Should not make any HTTP calls
        InstagramClient client = new InstagramClient(config, request -> {
            throw new AssertionError("Should not make HTTP request");
        });

        assertThat(client.resolveBusinessAccountId()).isEqualTo("custom_ig_id");
    }

    @Test
    void shouldThrowInformativeErrorWhenNoInstagramAccountIsLinked() {
        AppConfig config = new AppConfig("fb_page_123", "tok_valid", "v26.0", 10, 10, 2, -0.05);
        String discoveryJson = """
                {
                  "id": "fb_page_123"
                }
                """;

        InstagramClient client = new InstagramClient(config, request -> new FakeResponse(200, discoveryJson));

        assertThatThrownBy(client::resolveBusinessAccountId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Instagram Business/Professional Account linked");
    }

    @Test
    void shouldFetchMediaWithCursorPagination() {
        AppConfig config = new AppConfig("fb_page_123", "tok_valid", "v26.0", 10, 10, 10, 10, 10, 10, 2, -0.05, "178414000123");

        String page1 = """
                {
                  "data": [
                    {
                      "id": "media_1",
                      "caption": "Media 1",
                      "timestamp": "2026-09-08T12:00:00+0000",
                      "like_count": 10,
                      "comments": {"data": []}
                    }
                  ],
                  "paging": {
                    "next": "https://graph.facebook.com/v26.0/178414000123/media?after=cursor1"
                  }
                }
                """;

        String page2 = """
                {
                  "data": [
                    {
                      "id": "media_2",
                      "caption": "Media 2",
                      "timestamp": "2026-09-08T11:00:00+0000",
                      "like_count": 20,
                      "comments": {"data": []}
                    }
                  ]
                }
                """;

        Queue<String> responses = new LinkedList<>(List.of(page1, page2));
        InstagramClient client = new InstagramClient(config, request -> new FakeResponse(200, responses.poll()));

        List<InstagramMedia> media = client.fetchMedia();

        assertThat(media).hasSize(2);
        assertThat(media.get(0).id()).isEqualTo("media_1");
        assertThat(media.get(1).id()).isEqualTo("media_2");
    }

    @Test
    void shouldHandleMissingMessagingPermissionGracefully() {
        AppConfig config = new AppConfig("fb_page_123", "tok_valid", "v26.0", 10, 10, 10, 10, 10, 10, 2, -0.05, "178414000123");

        String permError = """
                {
                  "error": {
                    "message": "(#10) Application does not have instagram_manage_messages permission",
                    "type": "OAuthException",
                    "code": 10
                  }
                }
                """;

        InstagramClient client = new InstagramClient(config, request -> new FakeResponse(400, permError));

        List<InstagramConversation> convs = client.fetchConversations();
        assertThat(convs).isEmpty();
    }

    @Test
    void shouldHandleCapabilityErrorGracefully() {
        AppConfig config = new AppConfig("fb_page_123", "tok_valid", "v26.0", 10, 10, 10, 10, 10, 10, 2, -0.05, "178414000123");

        String capabilityError = """
                {
                  "error": {
                    "message": "(#3) Application does not have the capability to make this API call.",
                    "type": "OAuthException",
                    "code": 3
                  }
                }
                """;

        InstagramClient client = new InstagramClient(config, request -> new FakeResponse(400, capabilityError));

        List<InstagramConversation> convs = client.fetchConversations();
        assertThat(convs).isEmpty();
    }

    @Test
    void shouldFallbackToPageEndpointWhenDirectConversationsReturnsUnsupported() {
        AppConfig config = new AppConfig("fb_page_123", "tok_valid", "v26.0", 10, 10, 10, 10, 10, 10, 2, -0.05, "178414000123");

        String unsupportedError = """
                {
                  "error": {
                    "message": "Unsupported get request. Object with ID '178414000123' does not exist or does not support this operation.",
                    "type": "GraphMethodException",
                    "code": 100,
                    "error_subcode": 33
                  }
                }
                """;

        String validConversationsJson = """
                {
                  "data": [
                    {
                      "id": "t_101",
                      "updated_time": "2026-09-09T10:00:00+0000",
                      "participants": {"data": [{"id": "user_1", "username": "jane_doe"}]},
                      "messages": {
                        "data": [
                          {
                            "id": "m_1",
                            "message": "Hello from IG direct!",
                            "created_time": "2026-09-09T10:00:00+0000",
                            "from": {"id": "user_1", "username": "jane_doe"}
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        Queue<HttpResponse<String>> responses = new LinkedList<>(List.of(
                new FakeResponse(400, unsupportedError),
                new FakeResponse(200, validConversationsJson)
        ));

        InstagramClient client = new InstagramClient(config, request -> responses.poll());

        List<InstagramConversation> convs = client.fetchConversations();
        assertThat(convs).hasSize(1);
        assertThat(convs.get(0).id()).isEqualTo("t_101");
        assertThat(convs.get(0).messages()).hasSize(1);
        assertThat(convs.get(0).messages().get(0).message()).isEqualTo("Hello from IG direct!");
    }

    @Test
    void shouldEnrichCommentAuthorsDuringMediaFetch() {
        AppConfig config = new AppConfig("fb_page_123", "tok_valid", "v26.0", 10, 10, 10, 10, 10, 10, 2, -0.05, "178414000123");

        String mediaJson = """
                {
                  "data": [
                    {
                      "id": "media_99",
                      "caption": "Test Post",
                      "timestamp": "2026-09-08T12:00:00+0000",
                      "like_count": 5,
                      "comments": {
                        "data": [
                          {
                            "id": "c_1",
                            "text": "Hello world",
                            "timestamp": "2026-09-08T12:30:00+0000",
                            "username": "tawsifrahman113",
                            "like_count": 2
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        String profileHtml = """
                <html>
                <head>
                    <meta property="og:title" content="Tawsif Rahman TS (@tawsifrahman113) • Instagram photos" />
                    <meta property="og:image" content="https://cdn.example.com/avatar.jpg" />
                </head>
                </html>
                """;

        InstagramProfileResolver resolver = new InstagramProfileResolver(request -> new FakeResponse(200, profileHtml));
        InstagramClient client = new InstagramClient(
                config,
                request -> new FakeResponse(200, mediaJson),
                new InstagramResponseParser(),
                resolver
        );

        List<InstagramMedia> media = client.fetchMedia();
        assertThat(media).hasSize(1);
        assertThat(media.get(0).comments()).hasSize(1);

        var comment = media.get(0).comments().get(0);
        assertThat(comment.from().username()).isEqualTo("tawsifrahman113");
        assertThat(comment.from().name()).isEqualTo("Tawsif Rahman TS");
        assertThat(comment.from().pictureUrl()).isEqualTo("https://cdn.example.com/avatar.jpg");
    }
}
