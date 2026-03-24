package com.example.searchdemo.repo;

import com.example.searchdemo.domain.PostTerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostTermRepository extends JpaRepository<PostTerm, Long> {

    List<PostTerm> findByPostId(String postId);

    void deleteByPostId(String postId);

    /**
     * Find post IDs that contain ALL the given terms (AND search).
     * Uses GROUP BY / HAVING COUNT = number of terms.
     */
    @Query("""
        SELECT pt.postId
        FROM PostTerm pt
        WHERE pt.term IN :terms
        GROUP BY pt.postId
        HAVING COUNT(DISTINCT pt.term) = :termCount
        """)
    List<String> findPostIdsContainingAllTerms(
            @Param("terms") List<String> terms,
            @Param("termCount") long termCount);
}
