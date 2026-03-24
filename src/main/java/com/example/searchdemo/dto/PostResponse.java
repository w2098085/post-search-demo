package com.example.searchdemo.dto;

import com.example.searchdemo.domain.Post;

import java.time.LocalDateTime;

public record PostResponse(
        String id,
        String content,
        LocalDateTime createdAt,
        long likeCount
) {
    public static PostResponse from(Post post) {
        return new PostResponse(post.getId(), post.getContent(), post.getCreatedAt(), post.getLikeCount());
    }
}
