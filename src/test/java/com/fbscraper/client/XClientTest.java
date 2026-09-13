package com.fbscraper.client;

import com.fbscraper.config.XConfig;
import com.fbscraper.model.x.XPost;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XClientTest {

    record FakeResponse(int statusCode, String body) implements HttpResponse<String> {
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (key, value) -> true); }
        @Override public URI uri() { return null; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }

    @Test
    void shouldSearchRecentPostsAndAttachIncludedAuthor() {
        XConfig config = new XConfig("token", "", 10, 100, 100, false, false);
        AtomicReference<HttpRequest> request = new AtomicReference<>();
        String json = """
                {
                  "data": [{
                    "id": "101",
                    "text": "Do not buy EBL",
                    "author_id": "42",
                    "created_at": "2026-09-13T08:00:00Z",
                    "conversation_id": "101",
                    "lang": "en",
                    "public_metrics": {"like_count": 2, "reply_count": 0}
                  }],
                  "includes": {"users": [{
                    "id": "42",
                    "name": "Alice",
                    "username": "alice"
                  }]}
                }
                """;
        XClient client = new XClient(config, httpRequest -> {
            request.set(httpRequest);
            return new FakeResponse(200, json);
        });

        List<XPost> posts = client.searchRecentPosts("\"EBL\" -is:retweet");

        assertEquals(1, posts.size());
        assertEquals("alice", posts.getFirst().author().username());
        assertEquals(2, posts.getFirst().metrics().likeCount());
        assertTrue(request.get().uri().toString().contains("/tweets/search/recent?"));
        assertEquals("Bearer token", request.get().headers().firstValue("Authorization").orElseThrow());
    }
}
