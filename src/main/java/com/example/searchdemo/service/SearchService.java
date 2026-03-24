package com.example.searchdemo.service;

import com.example.searchdemo.domain.Post;
import com.example.searchdemo.repository.PostRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    @PersistenceContext
    private EntityManager entityManager;

    private final PostRepository postRepository;
    private final Tokenizer tokenizer;

    @Transactional(readOnly = true)
    public List<Post> search(String query, String sortBy, int limit, int offset) {
        List<String> terms = tokenizer.tokenize(query);

        if (terms.isEmpty()) {
            return List.of();
        }

        String orderClause = "likes".equalsIgnoreCase(sortBy)
                ? "p.likeCount DESC, p.createdAt DESC"
                : "p.createdAt DESC";

        String jpql = """
                SELECT p FROM Post p
                INNER JOIN PostTerm pt ON p.id = pt.postId
                WHERE pt.term IN :terms
                GROUP BY p.id
                HAVING COUNT(DISTINCT pt.term) = :termCount
                ORDER BY %s
                """.formatted(orderClause);

        log.debug("Search query terms={}, sort={}, limit={}, offset={}", terms, sortBy, limit, offset);

        List<Post> results = entityManager.createQuery(jpql, Post.class)
                .setParameter("terms", terms)
                .setParameter("termCount", (long) terms.size())
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();

        return results;
    }

    @Transactional(readOnly = true)
    public long count(String query) {
        List<String> terms = tokenizer.tokenize(query);

        if (terms.isEmpty()) {
            return 0;
        }

        String jpql = """
                SELECT COUNT(DISTINCT p.id) FROM Post p
                INNER JOIN PostTerm pt ON p.id = pt.postId
                WHERE pt.term IN :terms
                GROUP BY p.id
                HAVING COUNT(DISTINCT pt.term) = :termCount
                """;

        List<Long> counts = entityManager.createQuery(jpql, Long.class)
                .setParameter("terms", terms)
                .setParameter("termCount", (long) terms.size())
                .getResultList();

        return counts.size();
    }
}
