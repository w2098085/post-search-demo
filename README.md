# Post Search Demo

A Spring Boot 3.3.2 post search system using a custom inverted index backed by an H2 in-memory database. No Elasticsearch required.

## Quick Start

```bash
mvn clean install && mvn spring-boot:run
```

The application starts on **http://localhost:8080**.

## API Endpoints

### Create a Post

```bash
curl -s -X POST http://localhost:8080/api/posts \
  -H "Content-Type: application/json" \
  -d '{"content": "hello world spring boot"}' | jq
```

### Like a Post

```bash
curl -s -X POST http://localhost:8080/api/posts/{id}/like \
  -H "Content-Type: application/json" \
  -d '{"userId": "user1"}' | jq
```

Liking is **idempotent** — the same user liking the same post twice returns `success: false` the second time and does not increment the count.

### Search Posts

```bash
# Search by keyword, sort by recency (default)
curl -s "http://localhost:8080/api/search?q=hello&sort=recent&limit=10&offset=0" | jq

# Search by keyword, sort by like count
curl -s "http://localhost:8080/api/search?q=hello&sort=likes&limit=10&offset=0" | jq

# Multi-word search (AND semantics — post must contain ALL terms)
curl -s "http://localhost:8080/api/search?q=hello+world&sort=recent" | jq
```

### H2 Console (dev only)

Navigate to **http://localhost:8080/h2-console** and use JDBC URL `jdbc:h2:mem:testdb`.

---

## System Design

### Custom Inverted Index

Posts are tokenized at **write time** and stored in the `post_terms` table:

| Column  | Type    | Description                         |
|---------|---------|-------------------------------------|
| id      | BIGINT  | PK (auto-increment)                 |
| postId  | VARCHAR | FK to posts.id                      |
| term    | VARCHAR | Lowercase alphanumeric token        |

A unique constraint on `(postId, term)` prevents duplicate entries.

**Tokenization** splits on any non-alphanumeric character, lowercases every token, deduplicates, and sorts the result. For example:

```
"Hello, World! Spring-Boot" → ["boot", "hello", "spring", "world"]
```

### Search Query Strategy

Search uses AND semantics: a post must contain **all** query terms to match. This is implemented with a `GROUP BY / HAVING` pattern in JPQL:

```sql
SELECT p FROM Post p
INNER JOIN PostTerm pt ON p.id = pt.postId
WHERE pt.term IN (:terms)
GROUP BY p.id
HAVING COUNT(DISTINCT pt.term) = :termCount
ORDER BY p.createdAt DESC   -- or p.likeCount DESC, p.createdAt DESC
```

This avoids self-joins and scales well for moderate data volumes.

### Why No Elasticsearch

- H2 in-memory database is sufficient for interview/demo purposes.
- The `post_terms` table acts as an inverted index mapping terms → post IDs.
- The SQL `GROUP BY / HAVING` pattern replicates AND-query behaviour.
- Adding Elasticsearch would introduce operational overhead (cluster management, serialisation, index lifecycle) with no benefit at this scale.

---

## Schema

```sql
CREATE TABLE posts (
    id         VARCHAR(36) PRIMARY KEY,
    content    TEXT        NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    like_count BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE post_terms (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id VARCHAR(36) NOT NULL,
    term    VARCHAR(255) NOT NULL,
    UNIQUE (post_id, term)
);

CREATE TABLE post_likes (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id    VARCHAR(36)  NOT NULL,
    user_id    VARCHAR(255) NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    UNIQUE (post_id, user_id)
);
```

## Edge Cases

| Scenario | Behaviour |
|---|---|
| Empty / blank query | Returns empty results |
| Single-term query | Normal inverted-index lookup |
| Multi-term AND query | All terms must appear in the post |
| Unknown `sort` value | Falls back to `recent` (created_at DESC) |
| User likes post twice | Second call returns `success: false`, count unchanged |
| Post not found on like | 404 with descriptive message |
| Content > 5000 chars | 400 validation error |
| Blank content | 400 validation error |
