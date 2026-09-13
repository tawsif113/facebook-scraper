package com.fbscraper.client;

import com.fbscraper.client.ig.InstagramProfileResolver;
import com.fbscraper.model.instagram.InstagramComment;
import com.fbscraper.model.instagram.InstagramMedia;
import com.fbscraper.model.instagram.InstagramUser;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InstagramProfileResolverTest {

    record FakeResponse(int statusCode, String body) implements HttpResponse<String> {
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (key, value) -> true); }
        @Override public URI uri() { return null; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    }

    @Test
    void shouldResolveFullNameAndAvatarFromPublicOpenGraphHtml() {
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta property="og:title" content="Tawsif Rahman TS (&#064;tawsifrahman113) &#x2022; Instagram photos and videos" />
                    <meta property="og:image" content="https://scontent.cdninstagram.com/pic.jpg?stp=dst-jpg&amp;ccb=7-5" />
                </head>
                <body></body>
                </html>
                """;

        InstagramProfileResolver resolver = new InstagramProfileResolver(request -> {
            assertThat(request.uri().toString()).isEqualTo("https://www.instagram.com/tawsifrahman113/");
            assertThat(request.headers().firstValue("User-Agent").orElse(""))
                    .contains("facebookexternalhit/1.1");
            return new FakeResponse(200, html);
        });

        InstagramUser user = new InstagramUser("123", "tawsifrahman113");
        InstagramUser resolved = resolver.resolve(user);

        assertThat(resolved.name()).isEqualTo("Tawsif Rahman TS");
        assertThat(resolved.username()).isEqualTo("tawsifrahman113");
        assertThat(resolved.pictureUrl()).isEqualTo("https://scontent.cdninstagram.com/pic.jpg?stp=dst-jpg&ccb=7-5");
    }

    @Test
    void shouldFallbackToUsernameWhenProfileHasNoCustomNameOrGenericTitle() {
        String html = """
                <html>
                <head>
                    <meta property="og:title" content="Instagram" />
                </head>
                </html>
                """;

        InstagramProfileResolver resolver = new InstagramProfileResolver(request -> new FakeResponse(200, html));
        InstagramUser user = new InstagramUser("456", "random_user");
        InstagramUser resolved = resolver.resolve(user);

        assertThat(resolved.name()).isEqualTo("random_user");
        assertThat(resolved.pictureUrl()).isNull();
    }

    @Test
    void shouldCacheResolvedProfileAndAvoidRepeatedNetworkCalls() {
        String html = """
                <html>
                <head>
                    <meta property="og:title" content="MD Nayem Sheikh Arko (@mdnayemsheikh) • Instagram photos" />
                    <meta property="og:image" content="https://scontent.cdninstagram.com/profile.jpg" />
                </head>
                </html>
                """;

        AtomicInteger callCount = new AtomicInteger(0);
        InstagramProfileResolver resolver = new InstagramProfileResolver(request -> {
            callCount.incrementAndGet();
            return new FakeResponse(200, html);
        });

        InstagramUser user1 = new InstagramUser("u1", "mdnayemsheikh");
        InstagramUser user2 = new InstagramUser("u1", "mdnayemsheikh");

        InstagramUser resolved1 = resolver.resolve(user1);
        InstagramUser resolved2 = resolver.resolve(user2);

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(resolved1.name()).isEqualTo("MD Nayem Sheikh Arko");
        assertThat(resolved2.name()).isEqualTo("MD Nayem Sheikh Arko");
        assertThat(resolved1.pictureUrl()).isEqualTo("https://scontent.cdninstagram.com/profile.jpg");
    }

    @Test
    void shouldHandleHttpErrorGracefullyWithoutThrowing() {
        InstagramProfileResolver resolver = new InstagramProfileResolver(request -> {
            throw new IOException("Connection refused");
        });

        InstagramUser user = new InstagramUser("789", "offline_user");
        InstagramUser resolved = resolver.resolve(user);

        assertThat(resolved.username()).isEqualTo("offline_user");
        assertThat(resolved.name()).isEqualTo("offline_user");
    }

    @Test
    void shouldRecursivelyResolveMediaAndNestedCommentReplies() {
        String author1Html = """
                <html>
                <head>
                    <meta property="og:title" content="Parent Commenter (@parent_user) • Instagram" />
                    <meta property="og:image" content="https://cdn.example.com/p.jpg" />
                </head>
                </html>
                """;

        String author2Html = """
                <html>
                <head>
                    <meta property="og:title" content="Reply Commenter (@reply_user) • Instagram" />
                </head>
                </html>
                """;

        InstagramProfileResolver resolver = new InstagramProfileResolver(request -> {
            String uri = request.uri().toString();
            if (uri.contains("parent_user")) {
                return new FakeResponse(200, author1Html);
            }
            if (uri.contains("reply_user")) {
                return new FakeResponse(200, author2Html);
            }
            return new FakeResponse(404, "Not Found");
        });

        InstagramComment reply = new InstagramComment(
                "c2",
                "nested reply text",
                Instant.now(),
                new InstagramUser("id2", "reply_user"),
                0,
                List.of()
        );

        InstagramComment comment = new InstagramComment(
                "c1",
                "top level text",
                Instant.now(),
                new InstagramUser("id1", "parent_user"),
                5,
                List.of(reply)
        );

        InstagramMedia media = new InstagramMedia(
                "m1",
                "Post caption",
                "IMAGE",
                "https://cdn.example.com/media.jpg",
                "https://instagr.am/p/123",
                Instant.now(),
                10,
                2,
                List.of(comment)
        );

        InstagramMedia resolvedMedia = resolver.resolveMedia(media);

        assertThat(resolvedMedia.comments()).hasSize(1);
        InstagramComment resolvedComment = resolvedMedia.comments().get(0);
        assertThat(resolvedComment.from().name()).isEqualTo("Parent Commenter");
        assertThat(resolvedComment.from().pictureUrl()).isEqualTo("https://cdn.example.com/p.jpg");

        assertThat(resolvedComment.replies()).hasSize(1);
        InstagramComment resolvedReply = resolvedComment.replies().get(0);
        assertThat(resolvedReply.from().name()).isEqualTo("Reply Commenter");
    }
}
