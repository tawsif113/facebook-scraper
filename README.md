# Social Monitor (Facebook, Instagram & X)

A Java 21 / Spring Boot social monitoring application for Facebook Pages, Instagram Professional accounts, and public X accounts. It collects posts/media, comments or replies, engagement data, reviews/messages where the platform exposes them, analyzes text with the project's local VADER implementation, and presents the results in a browser dashboard.

## Platforms

### Facebook

- Page posts and comments
- Nested replies
- Reaction totals and visible reactor identities
- Reviews/ratings
- Messenger conversations

### Instagram

- Professional-account media
- Comments and nested replies
- Like/comment counts
- Direct messages

### X

- Search recent public posts by product name, phrase, hashtag, mention, and X search operators
- Lookup a public account by `@username`
- Fetch that user's recent original posts
- Fetch recent replies to those posts and the public author attached to each returned reply
- Fetch public post metrics: likes, replies, reposts, and quotes
- Attempt to fetch liking-user and reposting-user identities where the X API permits the request
- Analyze both X posts and replies with the same local VADER analyzer used by the Meta collectors

X calls comments **replies**. Detailed reply reconstruction uses the post `conversation_id` and Recent Search, so older replies can be incomplete. The X sync response exposes warnings instead of pretending the detailed rows are complete.

See [`docs/X_INTEGRATION.md`](docs/X_INTEGRATION.md) for the X-specific data flow and endpoint mapping.

## Configuration

Copy the template:

```bash
cp src/main/resources/application.properties.template src/main/resources/application.properties
```

Then configure the credentials you need locally. Never commit real tokens.

```properties
server.port=8080

# Facebook / Instagram
fb.page-id=YOUR_FACEBOOK_PAGE_ID
fb.access-token=YOUR_PAGE_ACCESS_TOKEN
fb.api-version=v26.0
fb.feed-limit=100
fb.comment-limit=100
fb.nested-comment-limit=100
fb.reaction-limit=100
fb.conversation-limit=100
fb.message-limit=100
fb.max-pages=5
fb.negative-threshold=-0.05
fb.ig-account-id=

# X API v2
x.bearer-token=YOUR_X_BEARER_TOKEN
x.username=
x.search-query=
x.post-limit=10
x.reply-limit=100
x.engagement-user-limit=100
x.fetch-liking-users=true
x.fetch-reposting-users=true
```

Both values are optional because the X dashboard accepts a search query or public handle at sync time. Example query:

```text
("EBL" OR @YourXHandle OR #DontBuyEBL) -is:retweet
```

## Meta permissions

For the Facebook/Instagram collectors, generate a Page access token with the permissions required by the features you use, including:

- `pages_show_list`
- `pages_read_engagement`
- `pages_read_user_content`
- `pages_messaging` for Facebook Page inbox access
- `instagram_basic`
- `instagram_manage_comments`
- `instagram_manage_messages` for Instagram DMs

A typical Page-token lookup is:

```text
/me/accounts?fields=id,name,access_token,tasks
```

Meta may expose aggregate engagement counts while withholding some user identities depending on permissions, access level, and privacy restrictions. The application therefore treats user-identity lists as potentially partial.

## X data flow

```text
Dashboard search query or @username
        |
        v
POST /api/sync?platform=x&query=<query>
or   /api/sync?platform=x&username=<handle>
        |
        v
XSyncService
        |
        +--> GET /2/tweets/search/recent
        |       query=<brand query>
        |       expansions=author_id,referenced_posts
        |
        |   OR account mode:
        |
        +--> GET /2/users/by/username/{username}
        |
        +--> GET /2/users/{id}/tweets
        |       exclude=replies,retweets
        |
        +--> GET /2/tweets/search/recent
        |       query=conversation_id:{postId}
        |       expansions=author_id,referenced_posts
        |
        +--> GET /2/tweets/{postId}/liking_users
        |
        +--> GET /2/tweets/{postId}/retweeted_by
        |
        v
existing VaderAnalyzer
        |
        v
XSyncResult + output/x/*.json + dashboard
```

The first X implementation intentionally does not scrape HTML. It uses the supported X API and returns whatever identities the API makes available.

## API

Run a platform sync:

```http
POST /api/sync?platform=facebook
POST /api/sync?platform=instagram
POST /api/sync?platform=x&username=XDevelopers
POST /api/sync?platform=x&query=(%22EBL%22%20OR%20%23DontBuyEBL)%20-is%3Aretweet
```

Get the latest result:

```http
GET /api/status?platform=facebook
GET /api/status?platform=instagram
GET /api/status?platform=x
```

## Dashboard

Start the application and open:

```text
http://localhost:8080
```

The platform switcher contains **Facebook**, **Instagram**, and **X**. Selecting X lets you choose **Brand search** or **Account posts**, enter the query or public handle, and click **Sync now**.

The X views include:

- **Replies:** author, reply text, VADER score/label, likes, parent-post context, timestamp
- **X posts:** matched post author, text/permalink, sentiment, like/reply/repost/quote counts, visible liking users, visible reposting users
- **Profile summary:** account name/handle, avatar, bio, location when exposed, follower/following/post counts

Facebook and Instagram retain platform-appropriate comments/engagement views; Facebook also exposes reviews and both Meta platforms can expose messages when the required permissions are available.

## JSON exports

Sync data is written beneath `output/`.

For X:

```text
output/x/
├── sync-result.json
├── replies.json
└── post-engagement.json
```

The Meta collectors continue to write their platform-specific export files through `DataExportService`.

## Run

```bash
./gradlew bootRun
```

Or:

```bash
./gradlew bootJar
java -jar build/libs/facebook-scraper-1.0.0-SNAPSHOT.jar
```

## Tests

```bash
./gradlew test
```

## Main components

```text
src/main/java/com/fbscraper/
├── App.java
├── client/
│   ├── FacebookClient.java
│   ├── GraphResponseParser.java
│   ├── GraphUrlBuilder.java
│   ├── InstagramClient.java
│   ├── InstagramResponseParser.java
│   ├── InstagramUrlBuilder.java
│   ├── XClient.java
│   ├── XResponseParser.java
│   └── XUrlBuilder.java
├── config/
│   ├── AppConfig.java
│   └── XConfig.java
├── model/
│   ├── facebook/
│   ├── instagram/
│   └── x/
├── sentiment/VaderAnalyzer.java
├── service/
│   ├── DataExportService.java
│   ├── SentimentSyncService.java
│   ├── XDataExportService.java
│   └── XSyncService.java
└── web/SyncApiController.java

src/main/resources/
├── application.properties.template
├── vader_lexicon.txt
└── static/index.html
```

## Notes on platform limits

- X does not have Facebook-style `LIKE/LOVE/HAHA/...` reaction categories. For X, engagement is modeled as likes, replies, reposts, and quotes.
- The current X reply collector uses Recent Search. It is intended for recent monitoring, not historical archival of every reply ever made to an old post.
- The current implementation requests at most 100 liking users and 100 reposting users per post. Aggregate counters may be larger than the identity lists shown.
- Protected X accounts are not supported by this app-only public-data flow.
- API plans, credits, rate limits, and endpoint access are controlled by Meta/X and can change independently of this codebase.
