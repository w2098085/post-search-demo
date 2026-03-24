package com.example.searchdemo.api;

import com.example.searchdemo.api.dto.*;
import com.example.searchdemo.domain.Post;
import com.example.searchdemo.service.PostService;
import com.example.searchdemo.service.SearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final SearchService searchService;

    /**
     * Create a new post.
     * POST /api/posts
     */
    @PostMapping("/posts")
    public ResponseEntity<PostResponse> createPost(@Valid @RequestBody CreatePostRequest request) {
        log.info("Creating post with content length={}", request.getContent().length());
        Post post = postService.createPost(request.getContent());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(post));
    }

    /**
     * Like a post (idempotent).
     * POST /api/posts/{postId}/like
     */
    @PostMapping("/posts/{postId}/like")
    public ResponseEntity<LikeResponse> likePost(
            @PathVariable String postId,
            @Valid @RequestBody LikeRequest request) {
        log.info("User {} liking post {}", request.getUserId(), postId);
        boolean success = postService.likePost(postId, request.getUserId());
        return ResponseEntity.ok(new LikeResponse(success));
    }

    /**
     * Search posts.
     * GET /api/search?q=...&sort=recent|likes&limit=10&offset=0
     */
    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(name = "sort", defaultValue = "recent") String sort,
            @RequestParam(name = "limit", defaultValue = "10") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        log.info("Search query='{}' sort='{}' limit={} offset={}", query, sort, limit, offset);

        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest().build();
        }
        if (offset < 0) {
            return ResponseEntity.badRequest().build();
        }

        List<Post> posts = searchService.search(query, sort, limit, offset);
        List<PostResponse> results = posts.stream().map(this::toResponse).toList();
        return ResponseEntity.ok(new SearchResponse(results));
    }

    /**
     * Handle IllegalArgumentException (e.g., post not found) as 404.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleNotFound(IllegalArgumentException ex) {
        log.warn("Not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    private PostResponse toResponse(Post post) {
        return PostResponse.builder()
                .id(post.getId())
                .content(post.getContent())
                .createdAt(post.getCreatedAt())
                .likeCount(post.getLikeCount())
                .build();
    }
}
