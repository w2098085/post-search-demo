package com.example.searchdemo.controller;

import com.example.searchdemo.domain.Post;
import com.example.searchdemo.dto.*;
import com.example.searchdemo.service.PostService;
import com.example.searchdemo.service.SearchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final SearchService searchService;

    @PostMapping("/api/posts")
    public ResponseEntity<PostResponse> createPost(@Valid @RequestBody CreatePostRequest request) {
        log.info("Creating post, contentLength={}", request.content().length());
        Post post = postService.createPost(request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(PostResponse.from(post));
    }

    @PostMapping("/api/posts/{postId}/like")
    public ResponseEntity<LikeResponse> likePost(
            @PathVariable String postId,
            @Valid @RequestBody LikeRequest request) {
        log.info("Like request postId={}, userId={}", postId, request.userId());
        boolean newLike = postService.likePost(postId, request.userId());
        if (newLike) {
            return ResponseEntity.ok(new LikeResponse(true, "Post liked successfully"));
        } else {
            return ResponseEntity.ok(new LikeResponse(false, "Already liked"));
        }
    }

    @GetMapping("/api/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(name = "sort", defaultValue = "recent") String sort,
            @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(100) int limit,
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset) {

        log.info("Search request q='{}', sort={}, limit={}, offset={}", query, sort, limit, offset);

        List<Post> posts = searchService.search(query, sort, limit, offset);
        long total = searchService.count(query);

        List<PostResponse> results = posts.stream().map(PostResponse::from).toList();
        return ResponseEntity.ok(new SearchResponse(results, (int) total));
    }
}
