package com.example.searchdemo.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "posts", indexes = {
    @Index(name = "idx_posts_created_at", columnList = "created_at"),
    @Index(name = "idx_posts_like_count", columnList = "like_count")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Post {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 5000)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "like_count", nullable = false)
    private long likeCount;
}
