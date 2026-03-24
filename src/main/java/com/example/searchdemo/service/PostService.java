package com.example.searchdemo.service;

import com.example.searchdemo.domain.Post;
import com.example.searchdemo.domain.PostLike;
import com.example.searchdemo.domain.PostTerm;
import com.example.searchdemo.repo.PostLikeRepository;
import com.example.searchdemo.repo.PostRepository;
import com.example.searchdemo.repo.PostTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final PostTermRepository postTermRepository;
    private final PostLikeRepository postLikeRepository;
    private final Tokenizer tokenizer;

    /**
     * Creates a new post, persists it, and indexes its terms.
     */
    @Transactional
    public Post createPost(String content) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Post post = Post.builder()
                .id(id)
                .content(content)
                .createdAt(now)
                .likeCount(0L)
                .build();

        postRepository.save(post);

        List<String> terms = tokenizer.tokenize(content);
        List<PostTerm> postTerms = terms.stream()
                .map(term -> PostTerm.builder().postId(id).term(term).build())
                .toList();
        postTermRepository.saveAll(postTerms);

        log.info("Created post id={} with {} terms", id, terms.size());
        return post;
    }

    /**
     * Idempotent like: returns true if a new like was recorded, false if already liked.
     * Atomically increments like_count on the post.
     */
    @Transactional
    public boolean likePost(String postId, String userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        Optional<PostLike> existing = postLikeRepository.findByPostIdAndUserId(postId, userId);
        if (existing.isPresent()) {
            log.debug("User {} already liked post {}", userId, postId);
            return false;
        }

        PostLike like = PostLike.builder()
                .postId(postId)
                .userId(userId)
                .createdAt(Instant.now())
                .build();
        postLikeRepository.save(like);

        post.setLikeCount(post.getLikeCount() + 1);
        postRepository.save(post);

        log.info("User {} liked post {}", userId, postId);
        return true;
    }
}
