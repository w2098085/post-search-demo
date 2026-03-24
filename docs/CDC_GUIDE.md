# CDC (Change Data Capture) Documentation & Implementation Guide

## Table of Contents

1. [CDC Concepts & Explanation](#1-cdc-concepts--explanation)
2. [CDC Architectures Explained](#2-cdc-architectures-explained)
3. [CDC Integration for Indexer Service](#3-cdc-integration-for-indexer-service)
4. [Implementation Guide: Debezium + Kafka](#4-implementation-guide-debezium--kafka)
5. [Comparison: Polling vs CDC vs Event Sourcing](#5-comparison-polling-vs-cdc-vs-event-sourcing)
6. [Step-by-Step Implementation for This System](#6-step-by-step-implementation-for-this-system)
7. [Code Examples](#7-code-examples)
8. [Operational Considerations](#8-operational-considerations)
9. [Comparison with Current Approach](#9-comparison-with-current-approach)

---

## 1. CDC Concepts & Explanation

### What is CDC (Change Data Capture)?

Change Data Capture (CDC) is a design pattern that **continuously detects and captures changes** (inserts, updates, deletes) made to a database and streams those changes to downstream consumers in near-real-time.

Instead of downstream systems querying the database for changes ("what changed since last time?"), CDC pushes a **changelog** of mutations as they happen, enabling event-driven architectures without requiring application-level instrumentation.

```
  ┌──────────────┐        Changes (INSERT/UPDATE/DELETE)
  │   Database   │ ──────────────────────────────────────►  Consumer A
  │  (MySQL /    │        Captured automatically
  │  PostgreSQL) │ ──────────────────────────────────────►  Consumer B
  └──────────────┘
```

### How Does CDC Work?

There are four primary mechanisms for capturing database changes:

#### 1. Log-Based CDC (binlog / WAL)
Databases maintain a **transaction log** for crash recovery. CDC tools read this log directly:
- **MySQL**: binary log (`binlog`) records every committed write
- **PostgreSQL**: Write-Ahead Log (`WAL`) records all page-level changes
- **MongoDB**: oplog (operations log) tracks all write operations

The CDC tool acts as a replica: it connects to the database, requests the log stream starting from a saved position, and re-plays each event.

```
MySQL binlog
────────────────────────────────────────────────────────────────────►
  [pos=100] INSERT posts   [pos=200] UPDATE posts  [pos=300] INSERT post_likes
                ▲                       ▲                    ▲
                └───────────────────────┴────────────────────┘
                         Debezium reads these events
                         and publishes to Kafka
```

#### 2. Query-Based CDC (Polling)
A periodic job polls the database using high-watermark queries:
```sql
SELECT * FROM posts WHERE created_at > :last_checked_at ORDER BY created_at;
```
Simple but limited — can only detect inserts (and updates if `updated_at` exists). Cannot detect hard deletes.

#### 3. Trigger-Based CDC
Database triggers fire on INSERT/UPDATE/DELETE and write to a shadow "CDC table":
```sql
CREATE TRIGGER after_post_insert
AFTER INSERT ON posts
FOR EACH ROW INSERT INTO posts_cdc (post_id, operation, payload, captured_at)
VALUES (NEW.id, 'INSERT', JSON_OBJECT(...), NOW());
```
A separate reader then processes the CDC table. Reliable but adds write amplification to every DML.

#### 4. Dual-Write (Application Level)
The application writes to both the primary store and a changelog (e.g., Kafka):
```java
postRepository.save(post);          // write to DB
kafkaProducer.send("posts", event); // write to Kafka
```
Simple to implement but risks **split-brain** — what if one write succeeds and the other fails?

---

### CDC vs Event Sourcing vs Message Queues

| Dimension | CDC | Event Sourcing | Message Queues (manual) |
|-----------|-----|----------------|-------------------------|
| **Source of truth** | Database state | Event log | Application code |
| **Capture mechanism** | Database-level (transparent) | Application must emit events | Application must publish |
| **Historical replay** | Limited (log retention) | Full (log IS the source) | Limited (queue retention) |
| **Schema coupling** | Low (DB schema) | High (event schema) | High (message schema) |
| **Missed events** | Impossible (log-based) | Impossible | Possible if publish fails |
| **Operational cost** | Medium | High | Low–Medium |
| **Best for** | Syncing DB → downstream | Audit trails, CQRS | Decoupled service calls |

**Key insight:** CDC is fundamentally different from Event Sourcing. CDC treats the database as the primary source of truth and derives events from it. Event Sourcing treats the *event log* as the source of truth and derives current state from events.

---

### Why CDC for the Indexer Service?

The post search system has two write paths that must be reflected in the search index:

1. **Post creation** → index the new post's terms in `post_terms`
2. **Post likes** → update `like_count` in the index so sort-by-popularity works

Without CDC:
- The indexer must poll periodically, introducing latency
- If the application crashes between the DB write and the Kafka publish, events are lost
- Bulk imports / migrations that bypass the application are invisible to the indexer

With CDC:
- **Every** committed DB write is captured — zero risk of missing events
- Indexing latency drops to ~50–200ms (binlog propagation + consumer lag)
- Application code stays clean — no dual-write complexity

---

## 2. CDC Architectures Explained

### 2.1 Log-Based CDC (MySQL binlog / PostgreSQL WAL)

#### How MySQL binlog Works

The MySQL binary log is a sequential, append-only file of all committed transactions. Each event contains:

```
+------------------+-----+-------------+-----------+--------+
| Log_name         | Pos | Event_type  | Server_id | End_log_pos |
+------------------+-----+-------------+-----------+-------------+
| mysql-bin.000001 | 4   | Format_desc | 1         | 123         |
| mysql-bin.000001 | 123 | Query       | 1         | 200         |
| mysql-bin.000001 | 200 | Table_map   | 1         | 248         |
| mysql-bin.000001 | 248 | Write_rows  | 1         | 312         |
| mysql-bin.000001 | 312 | Xid         | 1         | 343         |
+------------------+-----+-------------+-----------+-------------+
```

With `binlog_format = ROW`, each `Write_rows` / `Update_rows` / `Delete_rows` event contains the **before** and **after** image of every affected row. This is what CDC tools (Debezium, Maxwell) parse.

#### Log Position Tracking

CDC connectors maintain a **checkpoint** of the last processed log position:

```
Debezium offset (stored in Kafka):
{
  "file": "mysql-bin.000003",
  "pos":  14920,
  "gtid": "3E11FA47-71CA-11E1-9E33-C80AA9429562:1-23"
}
```

On connector restart, reading resumes from this position — ensuring **exactly-once** delivery when combined with Kafka transactions.

#### Advantages & Disadvantages

| ✅ Advantages | ❌ Disadvantages |
|---------------|-----------------|
| Captures ALL changes (insert, update, delete) | Requires `ROW` binlog format (not always default) |
| Near-zero latency (~ms after commit) | Log files can grow large; need retention policy |
| No impact on database write performance | Schema changes (DDL) require special handling |
| Captures changes from any client (migrations, raw SQL) | Database user needs `REPLICATION SLAVE` privilege |
| Supports initial snapshot + continuous streaming | Connector must be highly available to avoid gaps |

---

### 2.2 Query-Based CDC (Polling)

The indexer polls the DB on a schedule using a high-watermark timestamp or auto-increment ID:

```
Time: T0         T5s        T10s       T15s
      │           │          │          │
      ▼           ▼          ▼          ▼
  Poll DB     Poll DB    Poll DB    Poll DB
  (nothing)   (3 new     (1 new     (nothing)
               posts)     post)
```

**Polling query example (timestamp-based):**
```sql
SELECT * FROM posts
WHERE created_at > :last_polled_at
ORDER BY created_at ASC
LIMIT 500;
```

**Polling query example (ID-based):**
```sql
SELECT * FROM posts
WHERE id > :last_seen_id
ORDER BY id ASC
LIMIT 500;
```

#### Advantages & Disadvantages

| ✅ Advantages | ❌ Disadvantages |
|---------------|-----------------|
| Extremely simple to implement | Cannot detect hard deletes |
| No external infrastructure | Polling interval = consistency window (1–60s) |
| Works with any database | Clock skew can miss rows if timestamps are unreliable |
| Easy to debug and monitor | High polling frequency increases DB load |
| Interview-friendly (easy to explain) | N+1 problem if checking multiple tables separately |

---

### 2.3 Trigger-Based CDC

Database triggers capture every DML event and write to an audit / CDC table:

```sql
-- Trigger writes to shadow table
CREATE TRIGGER after_post_insert
AFTER INSERT ON posts
FOR EACH ROW
  INSERT INTO posts_cdc (operation, post_id, content, like_count, created_at, captured_at)
  VALUES ('INSERT', NEW.id, NEW.content, NEW.like_count, NEW.created_at, NOW());

CREATE TRIGGER after_post_update
AFTER UPDATE ON posts
FOR EACH ROW
  INSERT INTO posts_cdc (operation, post_id, content, like_count, created_at, captured_at)
  VALUES ('UPDATE', NEW.id, NEW.content, NEW.like_count, NEW.created_at, NOW());
```

A reader service polls `posts_cdc` and marks rows as processed:
```sql
SELECT * FROM posts_cdc WHERE processed = FALSE ORDER BY captured_at LIMIT 100;
UPDATE posts_cdc SET processed = TRUE WHERE id IN (...);
```

#### Advantages & Disadvantages

| ✅ Advantages | ❌ Disadvantages |
|---------------|-----------------|
| No external tool required | Write amplification: every DML touches two tables |
| Works with any MySQL/PostgreSQL version | Trigger errors can abort the original transaction |
| Captures all operations reliably | Hard to maintain: trigger logic must mirror schema |
| Shadow table is easy to query/debug | Increases lock contention on high-write tables |

---

### 2.4 Debezium (Popular CDC Framework)

#### What is Debezium?

Debezium is an **open-source distributed CDC platform** built on top of Apache Kafka Connect. It reads database transaction logs and publishes structured change events to Kafka topics.

```
┌─────────────┐    binlog/WAL    ┌─────────────────┐   Kafka events   ┌───────────────┐
│   MySQL /   │ ──────────────► │    Debezium     │ ──────────────► │    Kafka      │
│ PostgreSQL  │                  │ MySQL Connector │                  │    Topics     │
└─────────────┘                  └─────────────────┘                  └───────┬───────┘
                                                                               │
                                                               ┌───────────────▼────────────────┐
                                                               │         Consumers              │
                                                               │  (Indexer, Cache, Analytics)   │
                                                               └────────────────────────────────┘
```

#### Source Connectors

| Connector | Database | Log Source |
|-----------|----------|------------|
| `debezium-connector-mysql` | MySQL 5.7+, 8.x | binlog (ROW format) |
| `debezium-connector-postgres` | PostgreSQL 9.6+ | logical replication slot (WAL) |
| `debezium-connector-mongodb` | MongoDB 3.6+ | oplog |
| `debezium-connector-sqlserver` | SQL Server 2016+ | CDC tables |
| `debezium-connector-oracle` | Oracle 11g+ | LogMiner / Redo logs |

#### Sink Connectors (Kafka Connect Sinks)

Debezium itself is a **source** connector only. For forwarding to non-Kafka systems, Kafka Connect sink connectors are used (e.g., Elasticsearch Sink, JDBC Sink, S3 Sink).

#### Debezium Event Format

For a `posts` table INSERT, Debezium publishes to Kafka topic `{server}.{db}.posts`:

```json
{
  "schema": { "...": "..." },
  "payload": {
    "before": null,
    "after": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "content": "hello world",
      "created_at": 1700000000000,
      "like_count": 0
    },
    "source": {
      "version": "2.4.0.Final",
      "connector": "mysql",
      "db": "postdb",
      "table": "posts",
      "ts_ms": 1700000000123,
      "file": "mysql-bin.000003",
      "pos": 14920,
      "server_id": 1
    },
    "op": "c",
    "ts_ms": 1700000000456
  }
}
```

Operation codes: `c` = create, `u` = update, `d` = delete, `r` = read (snapshot).

#### How Debezium Fits into Our Architecture

```
  ┌─────────────────────────────────────────────────────────────────┐
  │                    Production Architecture                      │
  │                                                                 │
  │  ┌──────────────┐   binlog   ┌──────────────────────────────┐  │
  │  │   MySQL DB   │ ─────────► │  Debezium MySQL Connector    │  │
  │  │  (posts,     │            │  (Kafka Connect Worker)      │  │
  │  │  post_likes) │            └──────────────┬───────────────┘  │
  │  └──────────────┘                           │                  │
  │                                             ▼                  │
  │                              ┌──────────────────────────────┐  │
  │                              │         Kafka Cluster        │  │
  │                              │  Topics:                     │  │
  │                              │  • postdb.postdb.posts       │  │
  │                              │  • postdb.postdb.post_likes  │  │
  │                              └─────────────┬────────────────┘  │
  │                                            │                   │
  │                    ┌───────────────────────┼──────────────┐    │
  │                    ▼                       ▼              ▼    │
  │           ┌────────────────┐  ┌───────────────────┐  ┌──────┐ │
  │           │ Indexer Service│  │  Cache Invalidator│  │ Audit│ │
  │           │ (post_terms)   │  │  (Redis eviction) │  │ Svc  │ │
  │           └────────────────┘  └───────────────────┘  └──────┘ │
  └─────────────────────────────────────────────────────────────────┘
```

---

## 3. CDC Integration for Indexer Service

The indexer service is responsible for keeping `post_terms` (the inverted index) and `like_count` in sync with the `posts` and `post_likes` tables. Below are three design options.

---

### Design Option A: Debezium + Kafka ✅ Recommended for Production

```
  MySQL
  ┌──────────────┐
  │ posts        │
  │ post_likes   │ ──binlog──► Debezium ──► Kafka: posts_cdc, likes_cdc
  └──────────────┘                                        │
                                                          ▼
                                              ┌──────────────────────┐
                                              │   Indexer Service    │
                                              │  (Kafka Consumer)    │
                                              │                      │
                                              │  On INSERT post:     │
                                              │   tokenize → index   │
                                              │  On UPDATE post:     │
                                              │   re-index terms     │
                                              │  On INSERT like:     │
                                              │   incr like_count    │
                                              └──────────────────────┘
```

**Kafka Topic: `posts_cdc`**
```json
{
  "op": "c",
  "after": {
    "id": "abc123",
    "content": "hello world spring boot",
    "created_at": 1700000000000,
    "like_count": 0
  }
}
```

**Kafka Topic: `likes_cdc`**
```json
{
  "op": "c",
  "after": {
    "post_id": "abc123",
    "user_id": "user456",
    "created_at": 1700000000100
  }
}
```

| ✅ Benefits | ❌ Drawbacks |
|------------|-------------|
| Real-time (10–100ms latency) | Requires Kafka + Kafka Connect infrastructure |
| Exactly-once semantics (Kafka transactions) | Debezium connector is a new operational component |
| Captures all changes including migrations | Slightly complex initial setup |
| Indexer scales horizontally via consumer groups | Schema evolution needs careful handling |
| No polling overhead on the database | |

---

### Design Option B: Query-Based Polling (Timestamp) ✅ Best for MVP / Interview

```
  Every 5 seconds:
  ┌──────────────────────────────────────────────────────┐
  │                  Indexer Service                     │
  │                                                      │
  │  1. SELECT * FROM posts                              │
  │     WHERE created_at > last_indexed_at               │
  │     ORDER BY created_at ASC LIMIT 500                │
  │                                                      │
  │  2. For each new post:                               │
  │     tokenize(post.content) → insert post_terms       │
  │                                                      │
  │  3. SELECT * FROM post_likes                         │
  │     WHERE created_at > last_likes_checked_at         │
  │     ORDER BY created_at ASC LIMIT 500                │
  │                                                      │
  │  4. For each new like:                               │
  │     UPDATE posts SET like_count = like_count + 1     │
  │     (or maintain separate counter)                   │
  │                                                      │
  │  5. UPDATE indexer_metadata SET                      │
  │     last_indexed_at = :now                           │
  └──────────────────────────────────────────────────────┘
```

| ✅ Benefits | ❌ Drawbacks |
|------------|-------------|
| Minimal dependencies (just the DB) | Eventual consistency: 0–5s window |
| Simple to implement and understand | Cannot detect hard deletes |
| Easy to debug (just SQL queries) | Polling adds load even when there's nothing to index |
| No external infrastructure | Clock skew can miss rows |
| Interview-friendly | Miss updates to `content` if updated_at not tracked |

---

### Design Option C: Hybrid (MySQL binlog + Kafka via Maxwell)

Maxwell is a lighter-weight alternative to Debezium that reads MySQL binlog and writes JSON to Kafka/stdout. It has a smaller footprint than Debezium + Kafka Connect.

```
  MySQL
  ┌──────────────┐
  │  binlog      │ ──────► Maxwell ──► Kafka ──► Indexer Service
  └──────────────┘   (direct binlog     (JSON
                       reader, Java)    events)
```

Maxwell event for INSERT:
```json
{
  "database": "postdb",
  "table": "posts",
  "type": "insert",
  "ts": 1700000000,
  "data": {
    "id": "abc123",
    "content": "hello world",
    "like_count": 0,
    "created_at": "2024-01-01T00:00:00.000Z"
  }
}
```

| ✅ Benefits | ❌ Drawbacks |
|------------|-------------|
| Lower latency than Debezium (simpler pipeline) | MySQL-specific (no PostgreSQL support) |
| Simpler than full Debezium + Kafka Connect setup | Less mature than Debezium ecosystem |
| JSON output easy to consume | Still requires Kafka infrastructure |
| Supports WHERE-clause filtering | No exactly-once guarantees by default |

---

### Decision Tree: Choosing the Right Approach

```
Start: Which CDC approach should I use?
│
├─► Is this an interview / prototype / MVP?
│   └─► YES ──► Use Option B (Query-Based Polling)
│               Simple, zero dependencies, easy to explain
│
├─► Do I need real-time indexing (latency < 500ms)?
│   └─► YES ──► Do I have Kafka infrastructure?
│               ├─► YES ──► Use Option A (Debezium + Kafka)
│               └─► NO  ──► Use Option C (Maxwell + Kafka)
│                           or set up Kafka first
│
├─► Do I need to capture hard DELETEs?
│   └─► YES ──► Must use log-based CDC (Option A or C)
│               Polling CANNOT detect deletes
│
├─► Do I need multiple downstream consumers?
│   └─► YES ──► Use Kafka-based approach (A or C)
│               Kafka fan-out serves multiple consumers
│
└─► Is simplicity / low ops burden the priority?
    └─► YES ──► Use Option B (Polling)
                Add CDC later when scale demands it
```

---

## 4. Implementation Guide: Debezium + Kafka

### 4a. Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Full CDC Architecture                               │
│                                                                             │
│  ┌─────────────────────────────────────┐                                    │
│  │           MySQL 8.x                │                                    │
│  │  ┌──────────┐  ┌────────────────┐  │                                    │
│  │  │  posts   │  │  post_likes    │  │  binlog (ROW format)               │
│  │  │  table   │  │  table         │  │ ─────────────────────────────────► │
│  │  └──────────┘  └────────────────┘  │                                    │
│  │  binlog: mysql-bin.000001..N       │  ┌──────────────────────────────┐  │
│  └─────────────────────────────────────┘  │  Kafka Connect Cluster       │  │
│                                            │  ┌────────────────────────┐  │  │
│                                            │  │  Debezium MySQL        │  │  │
│                                            │  │  Source Connector      │  │  │
│                                            │  │  (reads binlog)        │  │  │
│                                            │  └──────────┬─────────────┘  │  │
│                                            └─────────────┼────────────────┘  │
│                                                          │                   │
│                                                          ▼                   │
│                              ┌─────────────────────────────────────────┐    │
│                              │           Apache Kafka                  │    │
│                              │  ┌─────────────────────────────────┐   │    │
│                              │  │ Topic: postdb.postdb.posts       │   │    │
│                              │  │ (Partitioned by post_id)         │   │    │
│                              │  └─────────────────────────────────┘   │    │
│                              │  ┌─────────────────────────────────┐   │    │
│                              │  │ Topic: postdb.postdb.post_likes  │   │    │
│                              │  │ (Partitioned by post_id)         │   │    │
│                              │  └─────────────────────────────────┘   │    │
│                              └──────────────────┬──────────────────────┘    │
│                                                 │                           │
│                     ┌───────────────────────────┼──────────────────────┐   │
│                     ▼                           ▼                      ▼   │
│           ┌──────────────────┐    ┌─────────────────────┐   ┌────────────┐ │
│           │  Indexer Service │    │  Cache Invalidator   │   │  Audit     │ │
│           │                  │    │                      │   │  Service   │ │
│           │  Consumer group: │    │  Consumer group:     │   │            │ │
│           │  indexer-cg      │    │  cache-invalidator   │   │            │ │
│           │                  │    │                      │   │            │ │
│           │  ┌────────────┐  │    │  On like event:      │   │            │ │
│           │  │post_terms  │  │    │  DEL cache:post:{id} │   │            │ │
│           │  │(inv. index)│  │    │                      │   │            │ │
│           │  └────────────┘  │    └─────────────────────┘   └────────────┘ │
│           └──────────────────┘                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### 4b. MySQL Configuration

Enable ROW-format binary logging (required by Debezium):

```ini
# /etc/mysql/conf.d/binlog.cnf
[mysqld]
# Enable binary logging
log_bin           = mysql-bin
binlog_format     = ROW          # ROW required for Debezium (not STATEMENT or MIXED)
server_id         = 1            # Must be unique across all MySQL instances
binlog_row_image  = FULL         # Capture full row images (before + after)
expire_logs_days  = 7            # Retain binlog for 7 days
max_binlog_size   = 100M         # Rotate logs at 100MB

# Optional: GTID mode for more reliable position tracking
gtid_mode         = ON
enforce_gtid_consistency = ON
```

Grant Debezium the required privileges:

```sql
-- Create a dedicated Debezium user
CREATE USER 'debezium'@'%' IDENTIFIED BY 'strong_password_here';

GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT
    ON *.* TO 'debezium'@'%';

-- If using GTID mode, also grant:
GRANT LOCK TABLES ON *.* TO 'debezium'@'%';

FLUSH PRIVILEGES;
```

---

### 4c. Debezium Connector Configuration

**File: `debezium-mysql-connector.json`**

```json
{
  "name": "post-search-mysql-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "tasks.max": "1",

    "database.hostname": "mysql",
    "database.port": "3306",
    "database.user": "debezium",
    "database.password": "${file:/opt/kafka/secrets/mysql.properties:password}",
    "database.server.id": "184054",
    "topic.prefix": "postdb",

    "database.include.list": "postdb",
    "table.include.list": "postdb.posts,postdb.post_likes",

    "schema.history.internal.kafka.bootstrap.servers": "kafka:9092",
    "schema.history.internal.kafka.topic": "schema-changes.postdb",

    "include.schema.changes": "true",
    "snapshot.mode": "initial",

    "transforms": "unwrap,addTopic",
    "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
    "transforms.unwrap.drop.tombstones": "false",
    "transforms.unwrap.delete.handling.mode": "rewrite",
    "transforms.unwrap.add.fields": "op,table,source.ts_ms",

    "errors.tolerance": "all",
    "errors.log.enable": "true",
    "errors.log.include.messages": "true",
    "errors.deadletterqueue.topic.name": "post-search.dlq",
    "errors.deadletterqueue.topic.replication.factor": "3",
    "errors.deadletterqueue.context.headers.enable": "true",

    "key.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "key.converter.schemas.enable": "false",
    "value.converter.schemas.enable": "false"
  }
}
```

Deploy the connector via Kafka Connect REST API:
```bash
curl -X POST http://kafka-connect:8083/connectors \
  -H "Content-Type: application/json" \
  -d @debezium-mysql-connector.json
```

Check connector status:
```bash
curl http://kafka-connect:8083/connectors/post-search-mysql-connector/status
```

---

### 4d. Kafka Topics

**Topic: `postdb.postdb.posts`** (after SMT unwrap)

INSERT event:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "content": "hello world spring boot demo",
  "created_at": 1700000000000,
  "like_count": 0,
  "__op": "c",
  "__table": "posts",
  "__source_ts_ms": 1700000000123
}
```

UPDATE event (like_count incremented):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "content": "hello world spring boot demo",
  "created_at": 1700000000000,
  "like_count": 5,
  "__op": "u",
  "__table": "posts",
  "__source_ts_ms": 1700000050456
}
```

DELETE event (tombstone):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "__op": "d",
  "__table": "posts",
  "__source_ts_ms": 1700000100789
}
```

**Topic: `postdb.postdb.post_likes`**

INSERT event:
```json
{
  "id": 42,
  "post_id": "550e8400-e29b-41d4-a716-446655440000",
  "user_id": "user-789",
  "created_at": 1700000050123,
  "__op": "c",
  "__table": "post_likes",
  "__source_ts_ms": 1700000050200
}
```

---

### 4e. Indexer Service Consumer (Java / Spring Boot)

```java
package com.example.searchdemo.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CdcIndexerConsumer {

    private static final Logger log = LoggerFactory.getLogger(CdcIndexerConsumer.class);

    private final ObjectMapper objectMapper;
    private final PostTermsRepository postTermsRepository;
    private final PostRepository postRepository;
    private final Tokenizer tokenizer;
    private final ProcessedEventRepository processedEventRepository; // for dedup

    // --- Posts CDC Consumer ---

    @KafkaListener(
        topics = "postdb.postdb.posts",
        groupId = "indexer-consumer-group",
        containerFactory = "batchKafkaListenerContainerFactory"
    )
    @Transactional
    public void onPostEvent(
            List<ConsumerRecord<String, String>> records,
            Acknowledgment ack) {

        for (ConsumerRecord<String, String> record : records) {
            try {
                processPostRecord(record);
            } catch (Exception e) {
                log.error("Failed to process post CDC record offset={}: {}",
                    record.offset(), e.getMessage(), e);
                // Non-retriable error: log and continue (DLQ configured at connector)
            }
        }
        ack.acknowledge();
    }

    private void processPostRecord(ConsumerRecord<String, String> record) throws Exception {
        JsonNode event = objectMapper.readTree(record.value());

        // Idempotency: skip if already processed
        String dedupKey = "posts:" + record.partition() + ":" + record.offset();
        if (processedEventRepository.existsByKey(dedupKey)) {
            log.debug("Skipping duplicate event: {}", dedupKey);
            return;
        }

        String op = event.path("__op").asText();
        String postId = event.path("id").asText();

        switch (op) {
            case "c" -> handlePostInsert(postId, event);
            case "u" -> handlePostUpdate(postId, event);
            case "d" -> handlePostDelete(postId);
            case "r" -> handlePostInsert(postId, event); // snapshot read
            default  -> log.warn("Unknown operation '{}' for post {}", op, postId);
        }

        processedEventRepository.markProcessed(dedupKey);
    }

    private void handlePostInsert(String postId, JsonNode event) {
        String content = event.path("content").asText();
        List<String> terms = tokenizer.tokenize(content);

        // Delete any stale terms (safe for inserts, no-op)
        postTermsRepository.deleteByPostId(postId);

        // Insert fresh terms
        List<PostTerm> postTerms = terms.stream()
            .map(term -> new PostTerm(postId, term))
            .toList();
        postTermsRepository.saveAll(postTerms);

        log.info("Indexed {} terms for post {}", terms.size(), postId);
    }

    private void handlePostUpdate(String postId, JsonNode event) {
        // Re-index terms in case content changed
        handlePostInsert(postId, event);
    }

    private void handlePostDelete(String postId) {
        postTermsRepository.deleteByPostId(postId);
        log.info("Removed index entries for deleted post {}", postId);
    }

    // --- Post Likes CDC Consumer ---

    @KafkaListener(
        topics = "postdb.postdb.post_likes",
        groupId = "indexer-consumer-group",
        containerFactory = "batchKafkaListenerContainerFactory"
    )
    @Transactional
    public void onLikeEvent(
            List<ConsumerRecord<String, String>> records,
            Acknowledgment ack) {

        for (ConsumerRecord<String, String> record : records) {
            try {
                processLikeRecord(record);
            } catch (Exception e) {
                log.error("Failed to process like CDC record offset={}: {}",
                    record.offset(), e.getMessage(), e);
            }
        }
        ack.acknowledge();
    }

    private void processLikeRecord(ConsumerRecord<String, String> record) throws Exception {
        JsonNode event = objectMapper.readTree(record.value());

        String dedupKey = "likes:" + record.partition() + ":" + record.offset();
        if (processedEventRepository.existsByKey(dedupKey)) {
            return;
        }

        String op = event.path("__op").asText();
        String postId = event.path("post_id").asText();

        if ("c".equals(op)) {
            // Like inserted — increment counter in the index
            postRepository.incrementLikeCount(postId);
            log.info("Incremented like_count for post {}", postId);
        } else if ("d".equals(op)) {
            // Like deleted (unlike) — decrement counter
            postRepository.decrementLikeCount(postId);
            log.info("Decremented like_count for post {}", postId);
        }

        processedEventRepository.markProcessed(dedupKey);
    }
}
```

**Kafka Consumer Configuration:**

```java
package com.example.searchdemo.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "indexer-consumer-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);  // manual ack
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); // exactly-once
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> batchKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setConcurrency(3); // 3 threads = 3 partitions max parallelism
        return factory;
    }
}
```

---

### 4f. Cache Invalidation

When a post's `like_count` changes, cached search results containing that post must be invalidated:

```java
package com.example.searchdemo.cache;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class CacheInvalidationService {

    private static final String POST_KEY_PREFIX    = "post:";
    private static final String SEARCH_KEY_PREFIX  = "search:";

    private final RedisTemplate<String, String> redisTemplate;

    public CacheInvalidationService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Invalidate a single post's cache entry.
     * Called when like_count changes.
     */
    public void invalidatePost(String postId) {
        String key = POST_KEY_PREFIX + postId;
        redisTemplate.delete(key);
    }

    /**
     * Invalidate all search result pages that contain a given post.
     * Strategy: maintain a reverse index  search-result-page → [post_ids].
     * On post change, look up which search pages reference it and evict them.
     *
     * Simpler alternative: use short TTL (e.g., 30s) and accept eventual consistency.
     */
    public void invalidateSearchResultsContaining(String postId) {
        // Use SCAN (cursor-based) instead of KEYS to avoid blocking the Redis server.
        // KEYS scans the entire keyspace in a single O(N) call and can stall
        // other Redis clients for hundreds of milliseconds under load.
        String pattern = SEARCH_KEY_PREFIX + "*";
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        List<String> toDelete = new ArrayList<>();
        try (Cursor<byte[]> cursor = redisTemplate.executeWithStickyConnection(
                conn -> conn.scan(options))) {
            while (cursor.hasNext()) {
                String key = new String(cursor.next());
                if (key.contains(postId)) {
                    toDelete.add(key);
                }
            }
        }
        if (!toDelete.isEmpty()) {
            redisTemplate.delete(toDelete);
        }
    }

    /**
     * Cascade invalidation triggered from the CDC consumer.
     */
    public void onPostLikeChange(String postId) {
        invalidatePost(postId);
        invalidateSearchResultsContaining(postId);
    }
}
```

**Cache Invalidation Flow:**

```
  CDC Event: post_likes INSERT (like on post "abc123")
  │
  ▼
  CdcIndexerConsumer.onLikeEvent()
  │
  ├─► postRepository.incrementLikeCount("abc123")   [DB update]
  │
  └─► cacheInvalidationService.onPostLikeChange("abc123")
      │
      ├─► Redis DEL  post:abc123                     [direct entry]
      │
      └─► Redis DEL  search:*:abc123:*               [search pages]
```

**TTL Fallback (eventual consistency safety net):**

```java
// Even without explicit invalidation, cache entries expire after TTL
redisTemplate.opsForValue().set(
    POST_KEY_PREFIX + postId,
    serialized,
    Duration.ofSeconds(60)  // 60-second TTL as fallback
);
```

---

## 5. Comparison: Polling vs CDC vs Event Sourcing

| Aspect | Polling | CDC (Log-Based) | Event Sourcing |
|--------|---------|-----------------|----------------|
| **Latency** | 1s–60s | 10–200ms | Real-time (<10ms) |
| **Complexity** | Low | Medium | High |
| **Scalability** | Medium | High | High |
| **Storage overhead** | Low | Medium (log retention) | High (full event log) |
| **Consistency** | Eventual (stale window) | Near-real-time | Strong (by design) |
| **Detects deletes** | ❌ No | ✅ Yes | ✅ Yes |
| **Detects updates** | ✅ With updated_at | ✅ Yes | ✅ Yes |
| **Captures out-of-app writes** | ✅ Yes (next poll) | ✅ Yes | ❌ No |
| **External dependencies** | None | CDC tool + Kafka | Event store (Kafka/EventStoreDB) |
| **Schema coupling** | Low | Low | High (event schema is the API) |
| **Historical replay** | Limited | Partial (log retention) | Full |
| **Operational burden** | Very low | Medium | High |
| **Best use case** | Prototypes, low-frequency changes | Production real-time indexing | Audit trails, CQRS, full history |

---

## 6. Step-by-Step Implementation for This System

### Phase 1: Query-Based Polling (MVP)

Start with polling — simple, works immediately, interview-friendly.

#### Step 1: Create Metadata Table

```sql
CREATE TABLE indexer_metadata (
    key_name    VARCHAR(64) PRIMARY KEY,
    value       VARCHAR(256) NOT NULL,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Initialize watermarks
INSERT INTO indexer_metadata (key_name, value) VALUES
    ('last_indexed_post_at',  '1970-01-01 00:00:00'),
    ('last_indexed_likes_at', '1970-01-01 00:00:00');
```

#### Step 2: Post Polling Indexer

```java
package com.example.searchdemo.indexer;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class PostPollingIndexer {

    private static final int BATCH_SIZE = 500;

    private final PostRepository         postRepository;
    private final PostTermsRepository    postTermsRepository;
    private final IndexerMetadataRepository metadataRepository;
    private final Tokenizer              tokenizer;

    @Scheduled(fixedDelay = 5000)  // every 5 seconds
    @Transactional
    public void pollAndIndex() {
        Instant lastIndexedAt = metadataRepository.getWatermark("last_indexed_post_at");
        Instant batchEnd      = Instant.now();

        List<Post> newPosts = postRepository.findByCreatedAtAfterOrderByCreatedAtAsc(
            lastIndexedAt, BATCH_SIZE
        );

        for (Post post : newPosts) {
            indexPost(post);
        }

        if (!newPosts.isEmpty()) {
            // Use max(created_at) across the batch rather than the last element's value.
            // This guards against out-of-order delivery and identical timestamps —
            // the next poll will use WHERE created_at >= watermark (not >) combined
            // with idempotent term deletion to avoid double-processing.
            Instant newWatermark = newPosts.stream()
                .map(Post::getCreatedAt)
                .max(Comparator.naturalOrder())
                .orElse(lastIndexedAt);
            metadataRepository.setWatermark("last_indexed_post_at", newWatermark);
        }
    }

    private void indexPost(Post post) {
        List<String> terms = tokenizer.tokenize(post.getContent());
        postTermsRepository.deleteByPostId(post.getId());  // idempotent
        postTermsRepository.saveAll(
            terms.stream().map(t -> new PostTerm(post.getId(), t)).toList()
        );
    }
}
```

#### Step 3: Likes Polling Indexer

```java
@Component
public class LikePollingIndexer {

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void pollLikes() {
        Instant lastChecked = metadataRepository.getWatermark("last_indexed_likes_at");

        List<PostLike> newLikes = likeRepository.findByCreatedAtAfterOrderByCreatedAtAsc(
            lastChecked, 500
        );

        for (PostLike like : newLikes) {
            // like_count is already maintained transactionally by PostService,
            // but if we're using CDC as the source of truth, update here:
            postRepository.incrementLikeCount(like.getPostId());
        }

        if (!newLikes.isEmpty()) {
            Instant newWatermark = newLikes.get(newLikes.size() - 1).getCreatedAt();
            metadataRepository.setWatermark("last_indexed_likes_at", newWatermark);
        }
    }
}
```

---

### Phase 2: Upgrade to Debezium (Production)

Once traffic grows and polling latency becomes unacceptable, migrate to Debezium:

1. **Enable binlog** on MySQL (see Section 4b)
2. **Deploy Kafka** (or use managed MSK / Confluent Cloud)
3. **Deploy Kafka Connect** worker cluster
4. **Register Debezium connector** (see Section 4c)
5. **Replace polling indexer** with `CdcIndexerConsumer` (see Section 4e)
6. **Perform initial snapshot**: Debezium `snapshot.mode: initial` reindexes all existing data on first start
7. **Validate**: compare row counts between `post_terms` and expected index size

**Migration Strategy (zero-downtime):**

```
Step 1: Deploy Kafka + Debezium alongside existing polling indexer
Step 2: Register connector (takes initial snapshot)
Step 3: Wait for snapshot to complete and consumer lag = 0
Step 4: Deploy new CdcIndexerConsumer service
Step 5: Monitor both old (polling) and new (CDC) indexers in parallel
Step 6: After validation period (24h), disable polling indexer
Step 7: Monitor for 72h, then decommission polling indexer
```

---

### Phase 3: Add Cache Invalidation

```
Step 1: Deploy Redis cluster (or ElastiCache)
Step 2: Add CacheInvalidationService to existing services
Step 3: Wrap PostService.getPost() with Redis cache-aside pattern:
        - Cache hit  → return cached post
        - Cache miss → query DB, cache result with TTL=60s, return
Step 4: Wire CdcIndexerConsumer to call CacheInvalidationService.onPostLikeChange()
        on every like CDC event
Step 5: Monitor cache hit rate (target: >80% for popular posts)
```

---

## 7. Code Examples

### 7a. Simple Polling Indexer (Complete, Standalone)

```java
package com.example.searchdemo.indexer;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Component
public class SimplePollingIndexer {

    private final PostRepository postRepo;
    private final PostTermsRepository termsRepo;
    private final Tokenizer tokenizer;

    // In-memory watermark for simplicity (use DB for production)
    private Instant lastIndexedAt = Instant.EPOCH;

    @Scheduled(fixedDelayString = "${indexer.poll-interval-ms:5000}")
    @Transactional
    public void index() {
        List<Post> posts = postRepo.findNewSince(lastIndexedAt);
        if (posts.isEmpty()) return;

        for (Post post : posts) {
            termsRepo.deleteByPostId(post.getId());
            tokenizer.tokenize(post.getContent()).stream()
                .map(term -> new PostTerm(post.getId(), term))
                .forEach(termsRepo::save);
        }

        // Use max(created_at) to handle ties and out-of-order records safely.
        // The polling query should use >= on the next run, combined with idempotent
        // term deletion, so any records sharing the watermark timestamp are re-processed
        // without producing duplicate index entries.
        lastIndexedAt = posts.stream()
            .map(Post::getCreatedAt)
            .max(Comparator.naturalOrder())
            .orElse(lastIndexedAt);
        System.out.printf("Indexed %d posts, watermark=%s%n", posts.size(), lastIndexedAt);
    }
}
```

---

### 7b. Debezium Connector Configuration (Complete JSON)

```json
{
  "name": "post-search-mysql-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "tasks.max": "1",
    "database.hostname": "localhost",
    "database.port": "3306",
    "database.user": "debezium",
    "database.password": "secret",
    "database.server.id": "184054",
    "topic.prefix": "postdb",
    "database.include.list": "postdb",
    "table.include.list": "postdb.posts,postdb.post_likes",
    "schema.history.internal.kafka.bootstrap.servers": "localhost:9092",
    "schema.history.internal.kafka.topic": "schemahistory.postdb",
    "snapshot.mode": "initial",
    "snapshot.locking.mode": "minimal",
    "include.schema.changes": "false",
    "transforms": "unwrap",
    "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
    "transforms.unwrap.add.fields": "op,source.ts_ms",
    "transforms.unwrap.delete.handling.mode": "rewrite",
    "transforms.unwrap.drop.tombstones": "false",
    "key.converter": "org.apache.kafka.connect.json.JsonConverter",
    "key.converter.schemas.enable": "false",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false",
    "heartbeat.interval.ms": "5000",
    "errors.tolerance": "all",
    "errors.log.enable": "true",
    "errors.deadletterqueue.topic.name": "post-search.dlq"
  }
}
```

---

### 7c. Kafka Consumer for CDC Events (Complete, with Error Handling)

```java
package com.example.searchdemo.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IndexerCdcConsumer {

    private static final Logger log = LoggerFactory.getLogger(IndexerCdcConsumer.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final IndexerService indexerService;

    @KafkaListener(topics = "postdb.postdb.posts", groupId = "indexer-cg")
    public void consumePost(
            List<ConsumerRecord<String, String>> records,
            Acknowledgment ack) {

        boolean allSucceeded = true;
        for (ConsumerRecord<String, String> record : records) {
            try {
                handlePostCdcEvent(record);
            } catch (Exception e) {
                log.error("Failed at partition={} offset={}: {}",
                    record.partition(), record.offset(), e.getMessage());
                allSucceeded = false;
                // Event is routed to DLQ by connector error handler
            }
        }
        // Acknowledge regardless — failed records go to DLQ
        ack.acknowledge();

        if (!allSucceeded) {
            log.warn("Some records in batch failed and were sent to DLQ");
        }
    }

    @Retryable(
        retryFor = TransientIndexingException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    private void handlePostCdcEvent(ConsumerRecord<String, String> record) throws Exception {
        JsonNode event = mapper.readTree(record.value());
        String op     = event.path("__op").asText();
        String postId = event.path("id").asText();

        log.debug("Processing op={} postId={} offset={}", op, postId, record.offset());

        switch (op) {
            case "c", "r" -> indexerService.indexPost(postId, event.path("content").asText());
            case "u"      -> indexerService.reindexPost(postId, event.path("content").asText());
            case "d"      -> indexerService.deletePostIndex(postId);
            default       -> log.warn("Unknown op '{}', skipping", op);
        }
    }
}
```

---

### 7d. Idempotency & Deduplication Pattern

```java
/**
 * Idempotency guard using a processed_events table.
 * Prevents double-processing when consumers restart or rebalance.
 */
@Service
public class IdempotencyService {

    private final ProcessedEventRepository repo;

    /**
     * Returns true if the event was NOT previously processed (safe to process).
     * Atomically marks it as processed.
     */
    @Transactional
    public boolean tryProcess(String eventKey) {
        if (repo.existsByEventKey(eventKey)) {
            return false;  // already processed
        }
        repo.save(new ProcessedEvent(eventKey, Instant.now()));
        return true;
    }

    /**
     * Build a unique event key from Kafka metadata.
     * topic + partition + offset uniquely identifies a Kafka message.
     */
    public static String buildKey(ConsumerRecord<?, ?> record) {
        return record.topic() + ":" + record.partition() + ":" + record.offset();
    }
}
```

Usage in consumer:
```java
String key = IdempotencyService.buildKey(record);
if (!idempotencyService.tryProcess(key)) {
    log.debug("Duplicate event, skipping: {}", key);
    return;
}
// ... process the event
```

---

### 7e. Cache Invalidation Logic (Complete)

```java
@Service
public class CacheInvalidationConsumer {

    private final RedisTemplate<String, String> redis;

    @KafkaListener(topics = "postdb.postdb.post_likes", groupId = "cache-invalidator-cg")
    public void onLikeChange(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        for (ConsumerRecord<String, String> record : records) {
            try {
                JsonNode event  = mapper.readTree(record.value());
                String postId   = event.path("post_id").asText();
                String op       = event.path("__op").asText();

                if ("c".equals(op) || "d".equals(op)) {
                    // Like added or removed — invalidate post cache
                    redis.delete("post:" + postId);
                    // Also invalidate paginated search results (use scan, not KEYS in production)
                    redis.delete("search:popular:0:10");  // simple: invalidate first page
                }
            } catch (Exception e) {
                log.warn("Cache invalidation failed for record {}: {}", record.offset(), e.getMessage());
                // Non-critical: TTL will expire the stale entry
            }
        }
        ack.acknowledge();
    }
}
```

---

## 8. Operational Considerations

### 8.1 Monitoring CDC Lag

CDC lag = time between a DB write and the indexer processing the corresponding event.

**Key Metrics:**

| Metric | Tool | Alert Threshold |
|--------|------|-----------------|
| Debezium connector lag (ms) | JMX / Prometheus | > 5,000ms |
| Kafka consumer group lag | `kafka-consumer-groups.sh` | > 10,000 messages |
| Indexer processing rate (events/sec) | Micrometer | < expected rate |
| DLQ message count | Kafka / CloudWatch | > 0 (any DLQ = alert) |

**Check consumer lag:**
```bash
kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 \
  --group indexer-consumer-group \
  --describe

# Example output:
# TOPIC                     PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# postdb.postdb.posts        0          14230           14235           5
# postdb.postdb.post_likes   0          8910            8912            2
```

**Prometheus alert rule:**
```yaml
- alert: CdcLagHigh
  expr: kafka_consumer_group_lag{group="indexer-consumer-group"} > 10000
  for: 2m
  labels:
    severity: warning
  annotations:
    summary: "CDC consumer lag is {{ $value }} messages"
```

---

### 8.2 Handling Connector Failures

```
Failure Scenarios and Recovery:

  1. Debezium connector crashes (OOM, network, etc.)
     → Kafka Connect auto-restarts the connector task
     → Connector resumes from last committed offset
     → No data loss (binlog is still there)

  2. MySQL binlog purged before connector reads it
     → Connector enters ERROR state ("GTID gap detected")
     → Must perform full re-snapshot:
       PUT /connectors/{name}/config  { "snapshot.mode": "initial" }
     → Avoid by setting expire_logs_days ≥ connector offset age

  3. Indexer consumer crashes mid-batch
     → Consumer restarts and re-reads from last committed Kafka offset
     → Idempotency guard prevents double-processing

  4. MySQL primary fails over to replica
     → Update connector config: database.hostname → new primary
     → Connector performs re-snapshot from new primary
     → Use GTID mode to minimize re-snapshot scope
```

---

### 8.3 Schema Changes & DDL Handling

Debezium stores schema history in a dedicated Kafka topic (`schema-changes.postdb`). When a DDL statement (ALTER TABLE, etc.) is executed:

```
ALTER TABLE posts ADD COLUMN updated_at TIMESTAMP;

                          Schema History Topic
                          ─────────────────────
  MySQL binlog:           [Schema v1: posts(id, content, created_at, like_count)]
  ┌─────────────┐         [DDL: ALTER TABLE posts ADD COLUMN updated_at]
  │ DDL event   │ ──────► [Schema v2: posts(id, content, created_at, like_count, updated_at)]
  └─────────────┘
                          Debezium uses schema v2 for subsequent row events
```

**Best Practices for DDL:**
1. Add columns as `NULLABLE` — Debezium handles `null` gracefully
2. Coordinate schema changes with consumer code updates
3. Test DDL on staging before production
4. Avoid `RENAME COLUMN` — Debezium may not handle it cleanly in all versions

---

### 8.4 Initial Snapshot Strategy

On connector first start with `snapshot.mode: initial`, Debezium:

1. Acquires a global read lock (`FLUSH TABLES WITH READ LOCK`)
2. Records current binlog position
3. Reads all rows from monitored tables (SELECT *)
4. Releases the lock
5. Emits all rows as `"op": "r"` (read) events to Kafka
6. Switches to streaming mode from the recorded position

```
Timeline:
T=0     Lock acquired, position = binlog:000003:14920
T=0..N  Snapshot reads (may take minutes for large tables)
T=N     Lock released, streaming begins from pos 14920
T=N+    All new writes (since T=0) are streamed normally
```

**For large tables:** Use `snapshot.mode: schema_only` + external initial load tool (e.g., `mysqldump` + batch import) to avoid long-running locks.

---

### 8.5 Testing the CDC Pipeline

**Unit test: Consumer logic**
```java
@Test
void testHandlePostInsert() throws Exception {
    String payload = """
        {"id":"abc","content":"hello world","__op":"c","__source_ts_ms":1700000000}
        """;
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("postdb.postdb.posts", 0, 100, "abc", payload);

    consumer.handlePostCdcEvent(record);

    List<PostTerm> terms = postTermsRepository.findByPostId("abc");
    assertThat(terms).extracting("term").containsExactlyInAnyOrder("hello", "world");
}
```

**Integration test: End-to-end CDC pipeline**
```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"postdb.postdb.posts"})
class CdcIntegrationTest {

    @Test
    void postInsertedInDbAppearsInIndex() throws Exception {
        // 1. Insert into DB (simulating app write)
        postRepository.save(new Post("test-id", "spring boot kafka", Instant.now(), 0L));

        // 2. Publish CDC event to embedded Kafka (simulating Debezium)
        kafkaTemplate.send("postdb.postdb.posts", "test-id",
            "{\"id\":\"test-id\",\"content\":\"spring boot kafka\",\"__op\":\"c\"}");

        // 3. Wait for consumer to process
        Thread.sleep(2000);

        // 4. Verify index was updated
        List<PostTerm> terms = postTermsRepository.findByPostId("test-id");
        assertThat(terms).extracting("term")
            .containsExactlyInAnyOrder("spring", "boot", "kafka");
    }
}
```

**Chaos engineering:**
- Kill the Debezium connector pod and verify auto-restart with no data loss
- Stop the MySQL replica (if using replica for CDC) and verify failover
- Introduce consumer lag by pausing the indexer and verify recovery

---

## 9. Comparison with Current Approach

### Current Architecture

The current system uses **application-level dual-write**:

```
  Client Request
  │
  ▼
  PostService.createPost()
  │
  ├─► 1. INSERT into posts (DB)
  ├─► 2. INSERT into post_terms (DB — synchronous, same transaction)
  └─► 3. Optional: publish to Kafka (if event-driven indexing added)

  PostService.likePost()
  │
  ├─► 1. INSERT into post_likes (DB)
  └─► 2. UPDATE posts SET like_count = like_count + 1 (DB — atomic)
```

All writes are synchronous, strongly consistent, within a single DB transaction.

---

### With CDC: Database-Driven Indexing

```
  Client Request
  │
  ▼
  PostService.createPost()
  │
  └─► 1. INSERT into posts (DB) — that's it!

  Asynchronously:
  MySQL binlog ──► Debezium ──► Kafka ──► IndexerConsumer
                                                │
                                                └─► INSERT into post_terms
```

The application no longer manages the index — the database change automatically triggers indexing.

---

### Trade-Off Analysis

| Dimension | Current (App-Level) | CDC (Debezium) |
|-----------|---------------------|----------------|
| **Simplicity** | ✅ Simple, one place | ⚠️ More moving parts |
| **Consistency** | ✅ Strong (same TX) | ⚠️ Eventual (50–500ms lag) |
| **Captures all writes** | ❌ Only app writes | ✅ Any DB client |
| **Schema ownership** | ✅ App controls schema | ⚠️ Schema is DB-driven |
| **Failure handling** | ✅ TX rollback covers all | ⚠️ CDC consumer can fall behind |
| **Scaling** | ⚠️ Coupled to app | ✅ Indexer scales independently |
| **Ops burden** | ✅ Low | ⚠️ Kafka + Debezium to maintain |
| **Audit trail** | ❌ None | ✅ Full changelog in Kafka |
| **Replay capability** | ❌ None | ✅ Replay from Kafka offset |

---

### When to Switch to CDC

**Stay with application-level writes when:**
- The system is small / prototype / interview project
- All writes go through a single application
- Strong consistency between write and index is required
- Operations team has no Kafka expertise

**Switch to CDC when:**
- Multiple applications / batch jobs write directly to the database
- You run bulk migrations or data imports that bypass the application
- You need to replay historical events (re-index after schema change)
- Your write volume is high enough that sync indexing adds latency to the API
- You need the index to survive application restarts without re-index logic
- You want multiple consumers (indexer + cache invalidator + analytics) from the same change stream

---

### Summary

```
                    ┌─────────────────────────────────────┐
                    │         Decision Summary             │
                    └─────────────────────────────────────┘

  Stage 1: MVP / Interview
  ┌──────────────────────────────────────────────────────┐
  │  App-level sync writes  +  Simple polling indexer    │
  │  • Zero external dependencies                        │
  │  • Strong consistency                                │
  │  • Easy to explain                                   │
  └──────────────────────────────────────────────────────┘
                          ↓ (when scale demands it)

  Stage 2: Production at Moderate Scale
  ┌──────────────────────────────────────────────────────┐
  │  App-level writes  +  CDC polling (5s interval)      │
  │  • Slight decoupling                                 │
  │  • Still no external dependencies                    │
  │  • Acceptable ~5s eventual consistency               │
  └──────────────────────────────────────────────────────┘
                          ↓ (real-time requirement)

  Stage 3: Production at High Scale
  ┌──────────────────────────────────────────────────────┐
  │  MySQL binlog  →  Debezium  →  Kafka  →  Indexer     │
  │  • Real-time (50–200ms lag)                          │
  │  • Scales horizontally                               │
  │  • Captures ALL writes (app + migrations + imports)  │
  │  • Full audit trail in Kafka                         │
  └──────────────────────────────────────────────────────┘
```
