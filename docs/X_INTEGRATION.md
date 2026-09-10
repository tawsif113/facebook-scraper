# X API Integration

This branch adds X as a third platform beside Facebook and Instagram. The goal is to inspect one public X user, fetch their recent original posts, collect reply and engagement data where the X API permits it, and run the existing local VADER analyzer over the text.

## Data flow

```text
Dashboard: @username
        |
        v
POST /api/sync?platform=x&username=<handle>
        |
        v
XSyncService
        |
        +--> XClient.fetchUser(handle)
        |       GET /2/users/by/username/{username}
        |
        +--> XClient.fetchUserPosts(user)
        |       GET /2/users/{id}/tweets
        |       exclude=replies,retweets
        |
        +--> for each post with replies
        |       GET /2/tweets/search/recent
        |       query=conversation_id:{postId}
        |       expansions=author_id
        |
        +--> for each post with likes
        |       GET /2/tweets/{postId}/liking_users
        |
        +--> for each post with reposts
                GET /2/tweets/{postId}/retweeted_by

Text from posts and replies
        |
        v
existing VaderAnalyzer
        |
        v
XSyncResult -> dashboard + output/x/*.json
```

## What is collected

### Target user

- X user ID
- display name
- username
- bio
- profile image URL
- user-provided location
- verification/protected flags
- public follower/following/post counts

### Posts

- post ID and text
- creation time
- conversation ID
- language
- public like, reply, repost and quote counts
- permalink
- VADER compound score and sentiment level

The timeline request deliberately excludes replies and retweets so the dashboard starts from the selected user's own top-level posts.

### Replies (comments)

X calls comments on Posts **replies**. Replies are reconstructed with `conversation_id:{rootPostId}` through Recent Search. `expansions=author_id` supplies the public author records so the UI can show who wrote each fetched reply.

Important: Recent Search only covers the recent search window (currently seven days). A post may therefore report an all-time `reply_count` that is larger than the detailed replies returned here. The sync result exposes warnings when this can affect the dashboard.

### Reactions / engagement identities

X does not have Facebook-style LIKE/LOVE/HAHA reaction types. The integration treats these as separate engagement types:

- **Likes**: `/2/tweets/{id}/liking_users`
- **Reposts**: `/2/tweets/{id}/retweeted_by`
- **Quotes**: total public count is stored; quote-user detail is not fetched in this first version
- **Replies**: author identity is collected through Recent Search

The liking/reposting user list is capped by `x.engagement-user-limit` (maximum 100 per API page in this implementation) to avoid unexpectedly large API usage.

## Configuration

Copy `src/main/resources/application.properties.template` to your local `application.properties` and set:

```properties
x.bearer-token=YOUR_X_BEARER_TOKEN
x.username=
x.post-limit=10
x.reply-limit=100
x.engagement-user-limit=100
x.fetch-liking-users=true
x.fetch-reposting-users=true
```

Do not commit a real Bearer Token.

`x.username` is optional. The X tab accepts a username at runtime, for example `XDevelopers` or `@XDevelopers`.

## Dashboard/API usage

Open the dashboard, select **X**, enter a public handle, and click **Sync now**.

Equivalent request:

```http
POST /api/sync?platform=x&username=XDevelopers
```

Latest cached/persisted X result:

```http
GET /api/status?platform=x
```

Output files are written under:

```text
output/x/
  sync-result.json
  replies.json
  post-engagement.json
```

## Main implementation classes

```text
src/main/java/com/fbscraper/
  client/
    XClient.java
    XResponseParser.java
    XUrlBuilder.java
  config/
    XConfig.java
  model/x/
    XUser.java
    XPost.java
    XReply.java
    XPublicMetrics.java
    XReplyAnalysis.java
    XPostAnalysis.java
    XSyncResult.java
  service/
    XSyncService.java
    XDataExportService.java
```

The existing `VaderAnalyzer` is reused directly; there is no separate sentiment engine for X.

## X API references

- User lookup: https://docs.x.com/x-api/users/lookup/quickstart/user-lookup
- User posts: https://docs.x.com/x-api/users/get-posts
- Conversation IDs/replies: https://docs.x.com/x-api/fundamentals/conversation-id
- Recent Search: https://docs.x.com/x-api/posts/search-recent-posts
- Liking users: https://docs.x.com/x-api/posts/get-liking-users
- Reposted-by users: https://docs.x.com/x-api/posts/get-reposted-by

X API availability, credits, rate limits and access rules can change. API errors for optional reply/engagement detail are reported as warnings where possible so basic user/post collection can still complete.
