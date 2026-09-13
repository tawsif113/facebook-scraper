package com.fbscraper.client;

import com.fbscraper.client.ig.InstagramResponseParser;
import com.fbscraper.model.instagram.InstagramComment;
import com.fbscraper.model.instagram.InstagramConversation;
import com.fbscraper.model.instagram.InstagramMedia;
import com.fbscraper.model.instagram.InstagramMessage;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InstagramResponseParserTest {

    private final InstagramResponseParser parser = new InstagramResponseParser();

    @Test
    void shouldParseBusinessAccountId() {
        String json = """
                {
                  "instagram_business_account": {
                    "id": "17841400012345678",
                    "username": "brand_official",
                    "name": "Brand Official"
                  },
                  "id": "1214847765056124"
                }
                """;

        Optional<InstagramResponseParser.BusinessAccountInfo> info = parser.parseBusinessAccount(json);

        assertThat(info).isPresent();
        assertThat(info.get().id()).isEqualTo("17841400012345678");
        assertThat(info.get().username()).isEqualTo("brand_official");
        assertThat(info.get().name()).isEqualTo("Brand Official");
    }

    @Test
    void shouldReturnEmptyWhenNoInstagramAccountLinked() {
        String json = """
                {
                  "id": "1214847765056124"
                }
                """;

        Optional<InstagramResponseParser.BusinessAccountInfo> info = parser.parseBusinessAccount(json);

        assertThat(info).isEmpty();
    }

    @Test
    void shouldParseMediaPageWithCommentsAndReplies() {
        String json = """
                {
                  "data": [
                    {
                      "id": "179000111222333",
                      "caption": "Exciting new update! #tech",
                      "media_type": "IMAGE",
                      "media_url": "https://cdn.instagram.com/photo.jpg",
                      "permalink": "https://www.instagram.com/p/Cxyz123/",
                      "timestamp": "2026-09-08T14:32:00+0000",
                      "like_count": 88,
                      "comments_count": 2,
                      "comments": {
                        "data": [
                          {
                            "id": "comm_1",
                            "text": "This is great!",
                            "timestamp": "2026-09-08T14:40:00+0000",
                            "username": "alex_tech",
                            "like_count": 5,
                            "replies": {
                              "data": [
                                {
                                  "id": "comm_1_reply",
                                  "text": "Glad you like it!",
                                  "timestamp": "2026-09-08T14:50:00+0000",
                                  "username": "brand_official",
                                  "like_count": 1
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "paging": {
                    "next": "https://graph.facebook.com/v26.0/next-page-cursor"
                  }
                }
                """;

        InstagramResponseParser.MediaPage page = parser.parseMediaPage(json);

        assertThat(page.media()).hasSize(1);
        assertThat(page.nextUrl()).isEqualTo("https://graph.facebook.com/v26.0/next-page-cursor");

        InstagramMedia media = page.media().get(0);
        assertThat(media.id()).isEqualTo("179000111222333");
        assertThat(media.caption()).isEqualTo("Exciting new update! #tech");
        assertThat(media.likeCount()).isEqualTo(88);
        assertThat(media.commentsCount()).isEqualTo(2);

        assertThat(media.comments()).hasSize(1);
        InstagramComment comment = media.comments().get(0);
        assertThat(comment.id()).isEqualTo("comm_1");
        assertThat(comment.text()).isEqualTo("This is great!");
        assertThat(comment.from().name()).isEqualTo("alex_tech");
        assertThat(comment.likeCount()).isEqualTo(5);

        assertThat(comment.replies()).hasSize(1);
        InstagramComment reply = comment.replies().get(0);
        assertThat(reply.id()).isEqualTo("comm_1_reply");
        assertThat(reply.text()).isEqualTo("Glad you like it!");
        assertThat(reply.from().name()).isEqualTo("brand_official");
        assertThat(reply.likeCount()).isEqualTo(1);
    }

    @Test
    void shouldParseConversationsPage() {
        String json = """
                {
                  "data": [
                    {
                      "id": "t_1001",
                      "updated_time": "2026-09-08T16:00:00+0000",
                      "participants": {
                        "data": [
                          { "id": "user_42", "username": "customer_sam" },
                          { "id": "1784140012345678", "username": "brand_official" }
                        ]
                      },
                      "messages": {
                        "data": [
                          {
                            "id": "m_1",
                            "message": "When will my order arrive?",
                            "created_time": "2026-09-08T15:55:00+0000",
                            "from": { "id": "user_42", "username": "customer_sam" },
                            "to": { "data": [{ "id": "1784140012345678", "username": "brand_official" }] },
                            "attachments": { "data": [] }
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        InstagramResponseParser.ConversationPage page = parser.parseConversationsPage(json);

        assertThat(page.conversations()).hasSize(1);
        InstagramConversation conv = page.conversations().get(0);
        assertThat(conv.id()).isEqualTo("t_1001");
        assertThat(conv.participants()).hasSize(2);
        assertThat(conv.messages()).hasSize(1);

        InstagramMessage msg = conv.messages().get(0);
        assertThat(msg.id()).isEqualTo("m_1");
        assertThat(msg.message()).isEqualTo("When will my order arrive?");
        assertThat(msg.from().name()).isEqualTo("customer_sam");
    }
}
