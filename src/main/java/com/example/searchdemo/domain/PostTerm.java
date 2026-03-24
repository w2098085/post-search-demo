package com.example.searchdemo.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "post_terms", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"postId", "term"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String postId;

    @Column(nullable = false)
    private String term;
}
