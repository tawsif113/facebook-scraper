package com.fbscraper.client.ig;

import com.fbscraper.client.fb.FacebookClient;
import com.fbscraper.model.instagram.InstagramComment;
import com.fbscraper.model.instagram.InstagramMedia;
import com.fbscraper.model.instagram.InstagramUser;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InstagramProfileResolver {

    private static final String USER_AGENT =
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";

    private static final Pattern OG_TITLE_PATTERN = Pattern.compile(
            "<meta\\s+[^>]*property=[\"']og:title[\"'][^>]*content=[\"']([^\"']*)[\"']|<meta\\s+[^>]*content=[\"']([^\"']*)[\"'][^>]*property=[\"']og:title[\"']",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern OG_IMAGE_PATTERN = Pattern.compile(
            "<meta\\s+[^>]*property=[\"']og:image[\"'][^>]*content=[\"']([^\"']*)[\"']|<meta\\s+[^>]*content=[\"']([^\"']*)[\"'][^>]*property=[\"']og:image[\"']",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PAREN_HANDLE_PATTERN = Pattern.compile("\\s*\\(@?[^)]+\\).*$");
    private static final Pattern INSTAGRAM_SUFFIX_PATTERN = Pattern.compile("(?i)\\s*(?:•|&#x2022;|on)\\s*Instagram.*$");

    private final FacebookClient.HttpSender httpSender;
    private final Map<String, InstagramUser> cache = new ConcurrentHashMap<>();

    public InstagramProfileResolver() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(4))
                .build();
        this.httpSender = request -> client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public InstagramProfileResolver(FacebookClient.HttpSender httpSender) {
        this.httpSender = httpSender != null ? httpSender : (request -> {
            throw new IOException("No HTTP sender configured");
        });
    }

    public InstagramUser resolve(InstagramUser user) {
        if (user == null) {
            return InstagramUser.ANONYMOUS;
        }

        String username = user.username();
        if (username == null || username.isBlank()
                || username.equalsIgnoreCase("Instagram User")
                || username.equalsIgnoreCase("Anonymous")) {
            return user;
        }

        String key = normalizeUsername(username);
        InstagramUser cached = cache.get(key);
        if (cached != null) {
            return merge(user, cached);
        }

        InstagramUser resolved = fetchProfile(user, key);
        cache.put(key, resolved);
        return resolved;
    }

    public InstagramComment resolveComment(InstagramComment comment) {
        if (comment == null) {
            return null;
        }
        InstagramUser enrichedUser = resolve(comment.from());
        List<InstagramComment> enrichedReplies = comment.replies().stream()
                .map(this::resolveComment)
                .toList();

        return new InstagramComment(
                comment.id(),
                comment.text(),
                comment.timestamp(),
                enrichedUser,
                comment.likeCount(),
                enrichedReplies
        );
    }

    public InstagramMedia resolveMedia(InstagramMedia media) {
        if (media == null) {
            return null;
        }
        List<InstagramComment> enrichedComments = media.comments().stream()
                .map(this::resolveComment)
                .toList();

        return new InstagramMedia(
                media.id(),
                media.caption(),
                media.mediaType(),
                media.mediaUrl(),
                media.permalink(),
                media.timestamp(),
                media.likeCount(),
                media.commentsCount(),
                enrichedComments
        );
    }

    public List<InstagramMedia> resolveMediaList(List<InstagramMedia> mediaList) {
        if (mediaList == null) {
            return List.of();
        }
        return mediaList.stream().map(this::resolveMedia).toList();
    }

    private InstagramUser fetchProfile(InstagramUser original, String key) {
        String url = "https://www.instagram.com/" + key + "/";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpSender.send(request);
            if (response.statusCode() == 200 && response.body() != null) {
                return parseProfile(original, response.body());
            } else {
                System.out.printf("[InstagramProfileResolver] Profile fetch for @%s returned HTTP %d, using fallback%n",
                        key, response.statusCode());
            }
        } catch (Exception e) {
            System.out.printf("[InstagramProfileResolver] Could not resolve profile for @%s: %s%n",
                    key, e.getMessage());
        }

        // Return fallback preserving existing fields
        return original;
    }

    InstagramUser parseProfile(InstagramUser original, String html) {
        String name = extractOgTitle(html);
        String pictureUrl = extractOgImage(html);

        String cleanedName = cleanDisplayName(name, original.username());
        String finalPicture = (pictureUrl != null && !pictureUrl.isBlank())
                ? pictureUrl
                : original.pictureUrl();

        return new InstagramUser(
                original.id(),
                original.username(),
                cleanedName,
                finalPicture
        );
    }

    private String extractOgTitle(String html) {
        Matcher matcher = OG_TITLE_PATTERN.matcher(html);
        if (matcher.find()) {
            String val = matcher.group(1);
            if (val == null || val.isBlank()) {
                val = matcher.group(2);
            }
            if (val != null) {
                return HtmlUtils.htmlUnescape(val).trim();
            }
        }
        return null;
    }

    private String extractOgImage(String html) {
        Matcher matcher = OG_IMAGE_PATTERN.matcher(html);
        if (matcher.find()) {
            String val = matcher.group(1);
            if (val == null || val.isBlank()) {
                val = matcher.group(2);
            }
            if (val != null && !val.isBlank()) {
                String unescaped = HtmlUtils.htmlUnescape(val).trim();
                if (unescaped.startsWith("http")) {
                    return unescaped;
                }
            }
        }
        return null;
    }

    private String cleanDisplayName(String ogTitle, String fallbackUsername) {
        if (ogTitle == null || ogTitle.isBlank()) {
            return fallbackUsername;
        }

        String cleaned = PAREN_HANDLE_PATTERN.matcher(ogTitle).replaceFirst("").trim();
        cleaned = INSTAGRAM_SUFFIX_PATTERN.matcher(cleaned).replaceFirst("").trim();
        if (cleaned.isBlank() || cleaned.equalsIgnoreCase("Instagram")) {
            return fallbackUsername;
        }

        if (cleaned.startsWith("@") && cleaned.substring(1).equalsIgnoreCase(fallbackUsername)) {
            return fallbackUsername;
        }

        return cleaned;
    }

    private InstagramUser merge(InstagramUser original, InstagramUser cached) {
        String id = (original.id() != null && !original.id().isBlank()) ? original.id() : cached.id();
        String username = original.username();
        String name = (cached.name() != null && !cached.name().isBlank()) ? cached.name() : original.name();
        String picture = cached.pictureUrl() != null ? cached.pictureUrl() : original.pictureUrl();
        return new InstagramUser(id, username, name, picture);
    }

    private String normalizeUsername(String username) {
        return username.toLowerCase().replace("@", "").trim();
    }
}
