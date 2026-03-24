# Post Search Demo

A Spring Boot 3 REST API implementing a post search system backed by H2 (in-memory), using a **custom inverted index** (no Elasticsearch).

## Tech Stack

- Java 17 + Spring Boot 3.3.2
- Spring Data JPA + H2 (in-memory)
- Lombok for boilerplate reduction
- Bean Validation (Jakarta) for input validation

---

## Build & Run

### Prerequisites
- Java 17+
- Maven 3.8+

### Commands

```bash
# Build and run tests
mvn clean install

# Start the application
mvn spring-boot:run
```

The server starts on **http://localhost:8080**.

H2 console (for inspection): **http://localhost:8080/h2**
- JDBC URL: `jdbc:h2:mem:searchdemo`
- Username: `sa`, Password: *(empty)*

---

## API Endpoints

### Create a Post

```bash
curl -s -X POST http://localhost:8080/api/posts \
  -H "Content-Type: application/json" \
  -d '{"content": "Spring Boot is great for building REST APIs"}' | jq .
```

Response:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "content": "Spring Boot is great for building REST APIs",
  "createdAt": "2024-01-01T12:00:00Z",
  "likeCount": 0
}
```

---

### Like a Post (idempotent)

```bash
curl -s -X POST http://localhost:8080/api/posts/{postId}/like \
  -H "Content-Type: application/json" \
  -d '{"userId": "user123"}' | jq .
```

Response:
```json
{ "success": true }
```

Calling again with the same `userId` returns `{ "success": false }` (idempotent — no duplicate like).

---

### Search Posts

```bash
# Search by keyword, sorted by most recent (default)
curl -s "http://localhost:8080/api/search?q=spring+boot" | jq .

# Search sorted by most likes
curl -s "http://localhost:8080/api/search?q=spring+boot&sort=likes" | jq .

# Paginate: skip first 5, get next 10
curl -s "http://localhost:8080/api/search?q=rest&sort=recent&limit=10&offset=5" | jq .
```

Response:
```json
{
  "results": [
    {
      "id": "550e8400-...",
      "content": "Spring Boot is great for building REST APIs",
      "createdAt": "2024-01-01T12:00:00Z",
      "likeCount": 3
    }
  ]
}
```

---

## Full Example Session

```bash
# 1. Create some posts
POST1=$(curl -s -X POST http://localhost:8080/api/posts \
  -H "Content-Type: application/json" \
  -d '{"content":"Spring Boot REST API tutorial"}' | jq -r '.id')

POST2=$(curl -s -X POST http://localhost:8080/api/posts \
  -H "Content-Type: application/json" \
  -d '{"content":"Spring Boot JPA database tutorial"}' | jq -r '.id')

POST3=$(curl -s -X POST http://localhost:8080/api/posts \
  -H "Content-Type: application/json" \
  -d '{"content":"Java concurrency patterns"}' | jq -r '.id')

# 2. Like post1 twice (idempotent - second like is ignored)
curl -s -X POST http://localhost:8080/api/posts/$POST1/like \
  -H "Content-Type: application/json" -d '{"userId":"alice"}'

curl -s -X POST http://localhost:8080/api/posts/$POST1/like \
  -H "Content-Type: application/json" -d '{"userId":"alice"}'   # returns success: false

curl -s -X POST http://localhost:8080/api/posts/$POST1/like \
  -H "Content-Type: application/json" -d '{"userId":"bob"}'

# 3. Search: "spring boot" matches post1 and post2 (AND logic)
curl -s "http://localhost:8080/api/search?q=spring+boot&sort=likes"

# 4. Search: "tutorial" matches post1 and post2
curl -s "http://localhost:8080/api/search?q=tutorial&sort=recent"

# 5. Search: "java" only matches post3
curl -s "http://localhost:8080/api/search?q=java"
```

---

## System Design

### Architecture

```
Client → PostController → PostService / SearchService → H2 via JPA
```

### Custom Inverted Index

Search is implemented without Elasticsearch using a **database-backed inverted index**:

| Table        | Purpose                                          |
|--------------|--------------------------------------------------|
| `posts`      | Stores post content, timestamps, and `like_count` |
| `post_terms` | Inverted index: `(post_id, term)` pairs           |
| `post_likes` | Tracks which users liked which posts (unique constraint) |

**Write path** (create post):
1. Generate UUID for post ID
2. Save post to `posts` table
3. Tokenize content (lowercase, alphanumeric only)
4. Save `(post_id, term)` pairs to `post_terms` (deduplicated)

**Read path** (search):
1. Tokenize the query using the same tokenizer
2. Query `post_terms` with `WHERE term IN (:terms) GROUP BY post_id HAVING COUNT(DISTINCT term) = N`
   - This implements **AND semantics**: every term must appear in the post
3. Fetch matching posts from `posts` table
4. Sort by `created_at` DESC (recent) or `like_count` DESC (popular)
5. Apply `offset`/`limit` for pagination

**Like path** (idempotent):
1. Check `post_likes` for existing `(post_id, user_id)` pair
2. If absent: insert like record + increment `posts.like_count`
3. If present: return `success: false` (no duplicate)

### Tradeoffs & Scalability

| Aspect | Current approach | At scale |
|--------|-----------------|----------|
| Storage | H2 in-memory (dev) | PostgreSQL/MySQL |
| Search | SQL GROUP BY/HAVING | Dedicated search engine (Elasticsearch) |
| Tokenization | Simple split + lowercase | Stemming, stop-words, synonyms |
| Freshness | Immediate (synchronous write) | Near-real-time with async indexing |
| Likes | DB unique constraint | Redis counter + async DB sync |
