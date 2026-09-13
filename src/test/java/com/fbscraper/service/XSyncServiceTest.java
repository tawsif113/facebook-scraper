package com.fbscraper.service;

import com.fbscraper.client.XClient;
import com.fbscraper.config.AppConfig;
import com.fbscraper.config.XConfig;
import com.fbscraper.model.x.XPost;
import com.fbscraper.model.x.XPublicMetrics;
import com.fbscraper.model.x.XSyncResult;
import com.fbscraper.model.x.XUser;
import com.fbscraper.sentiment.VaderAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class XSyncServiceTest {

    @Test
    void shouldAnalyzeBrandSearchResultsAndPreserveAuthors(@TempDir Path tempDir) {
        String query = "(\"EBL\" OR #DontBuyEBL) -is:retweet";
        XConfig xConfig = new XConfig("token", "", query, 10, 100, 100, false, false);
        XUser author = new XUser(
                "42", "Alice", "alice", "", null, "Dhaka",
                false, false, 10, 5, 20
        );
        XPost matchedPost = new XPost(
                "101",
                "I hate this awful EBL service",
                author,
                Instant.parse("2026-09-13T08:00:00Z"),
                "101",
                "en",
                new XPublicMetrics(2, 0, 1, 0, 50)
        );
        XClient xClient = new XClient(xConfig, request -> {
            throw new AssertionError("Unexpected HTTP call");
        }) {
            @Override public List<XPost> searchRecentPosts(String requestedQuery) {
                assertThat(requestedQuery).isEqualTo(query);
                return List.of(matchedPost);
            }
        };
        AppConfig appConfig = new AppConfig("", "", "v26.0", 100, 100, 5, -0.05);
        XSyncService service = new XSyncService(
                xConfig,
                appConfig,
                xClient,
                VaderAnalyzer.createDefault(),
                new XDataExportService(tempDir)
        );

        XSyncResult result = service.sync(null, query);

        assertThat(result.sourceMode()).isEqualTo("search");
        assertThat(result.query()).isEqualTo(query);
        assertThat(result.totalPosts()).isEqualTo(1);
        assertThat(result.negativePosts()).isEqualTo(1);
        assertThat(result.negativePostRate()).isEqualTo(100.0);
        assertThat(result.postReactions().getFirst().author().username()).isEqualTo("alice");
        assertThat(result.warnings()).anyMatch(message -> message.contains("seven days"));
    }
}
