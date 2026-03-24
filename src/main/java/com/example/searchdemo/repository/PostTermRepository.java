package com.example.searchdemo.repository;

import com.example.searchdemo.domain.PostTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PostTermRepository extends JpaRepository<PostTerm, Long> {

    void deleteByPostId(String postId);

    List<PostTerm> findByPostId(String postId);

    List<PostTerm> findByTerm(String term);
}
