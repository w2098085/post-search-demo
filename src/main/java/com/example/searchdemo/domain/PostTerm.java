package com.example.searchdemo.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "post_terms", uniqueConstraints = {
    @UniqueConstraint(name = "uq_post_terms_post_term", columnNames = {"post_id", "term"})
}, indexes = {
    @Index(name = "idx_post_terms_term", columnList = "term"),
    @Index(name = "idx_post_terms_post", columnList = "post_id")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PostTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false, length = 36)
    private String postId;

    @Column(nullable = false, length = 64)
    private String term;
}
