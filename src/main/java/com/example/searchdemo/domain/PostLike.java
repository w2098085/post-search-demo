package com.example.searchdemo.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "post_likes", uniqueConstraints = {
    @UniqueConstraint(name = "uq_post_likes_post_user", columnNames = {"post_id", "user_id"})
}, indexes = {
    @Index(name = "idx_post_likes_post", columnList = "post_id"),
    @Index(name = "idx_post_likes_user", columnList = "user_id")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PostLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false, length = 36)
    private String postId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
