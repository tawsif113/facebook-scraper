package com.fbscraper.client;

import com.fbscraper.model.x.XReply;
import com.fbscraper.model.x.XUser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XResponseParserTest {

    private final XResponseParser parser = new XResponseParser();

    @Test
    void shouldParseUserAndCurrentPublicMetricNames() {
        String json = """
                {
                  "data": {
                    "id": "42",
                    "name": "Alice Example",
                    "username": "alice",
                    "description": "Support tester",
                    "profile_image_url": "https://img.example/alice.jpg",
                    "location": "Dhaka",
                    "verified": true,
                    "protected": false,
                    "public_metrics": {
                      "followers_count": 120,
                      "following_count": 55,
                      "post_count": 900
                    }
                  }
                }
                """;

        XUser user = parser.parseUser(json).orElseThrow();
        assertEquals("42", user.id());
        assertEquals("alice", user.username());
        assertEquals(120, user.followersCount());
        assertEquals(900, user.postCount());
        assertTrue(user.verified());
    }

    @Test
    void shouldParseRepliesAuthorsParentAndEngagementMetrics() {
        String json = """
                {
                  "data": [
                    {
                      "id": "102",
                      "text": "This support experience was awful",
                      "author_id": "200",
                      "conversation_id": "100",
                      "created_at": "2026-09-10T07:00:00Z",
                      "public_metrics": {
                        "like_count": 3,
                        "reply_count": 1,
                        "repost_count": 2,
                        "quote_count": 0,
                        "impression_count": 40
                      },
                      "referenced_posts": [
                        {"type": "replied_to", "id": "100"}
                      ]
                    }
                  ],
                  "includes": {
                    "users": [
                      {
                        "id": "200",
                        "name": "Bob",
                        "username": "bob",
                        "public_metrics": {
                          "followers_count": 10,
                          "following_count": 20,
                          "post_count": 30
                        }
                      }
                    ]
                  },
                  "meta": {"result_count": 1, "next_token": "NEXT"}
                }
                """;

        XResponseParser.ReplyPage page = parser.parseRepliesPage(json);
        List<XReply> replies = page.replies();
        assertEquals(1, replies.size());
        assertEquals("bob", replies.getFirst().author().username());
        assertEquals("100", replies.getFirst().parentPostId());
        assertEquals(3, replies.getFirst().metrics().likeCount());
        assertEquals(2, replies.getFirst().metrics().repostCount());
        assertEquals("NEXT", page.nextToken());
    }

    @Test
    void shouldAcceptLegacyRetweetAndTweetCountFields() {
        String userJson = """
                {"data":{"id":"1","name":"Legacy","username":"legacy","public_metrics":{"tweet_count":7}}}
                """;
        assertEquals(7, parser.parseUser(userJson).orElseThrow().postCount());

        String postJson = """
                {"data":[{"id":"9","text":"hello","author_id":"1","public_metrics":{"retweet_count":4}}]}
                """;
        XUser user = parser.parseUser(userJson).orElseThrow();
        assertEquals(4, parser.parsePostsPage(postJson, user).posts().getFirst().metrics().repostCount());
    }
}
