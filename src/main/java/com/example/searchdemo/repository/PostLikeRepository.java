package com.example.searchdemo.repository;

import com.example.searchdemo.domain.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    Optional<PostLike> findByPostIdAndUserId(String postId, String userId);

    long countByPostId(String postId);
}
