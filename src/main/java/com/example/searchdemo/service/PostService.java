package com.example.searchdemo.service;

import com.example.searchdemo.domain.Post;
import com.example.searchdemo.domain.PostLike;
import com.example.searchdemo.domain.PostTerm;
import com.example.searchdemo.repository.PostLikeRepository;
import com.example.searchdemo.repository.PostRepository;
import com.example.searchdemo.repository.PostTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final PostTermRepository postTermRepository;
    private final PostLikeRepository postLikeRepository;
    private final Tokenizer tokenizer;

    @Transactional
    public Post createPost(String content) {
        String id = UUID.randomUUID().toString();
        Post post = Post.builder()
                .id(id)
                .content(content)
                .createdAt(LocalDateTime.now())
                .likeCount(0)
                .build();
        postRepository.save(post);

        List<String> terms = tokenizer.tokenize(content);
        List<PostTerm> postTerms = terms.stream()
                .map(term -> PostTerm.builder()
                        .postId(id)
                        .term(term)
                        .build())
                .toList();
        postTermRepository.saveAll(postTerms);

        log.debug("Created post id={} with {} terms", id, terms.size());
        return post;
    }

    @Transactional
    public boolean likePost(String postId, String userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new com.example.searchdemo.exception.ResourceNotFoundException(
                        "Post not found: " + postId));

        if (postLikeRepository.findByPostIdAndUserId(postId, userId).isPresent()) {
            log.debug("User {} already liked post {}", userId, postId);
            return false;
        }

        PostLike like = PostLike.builder()
                .postId(postId)
                .userId(userId)
                .createdAt(LocalDateTime.now())
                .build();
        postLikeRepository.save(like);

        post.setLikeCount(post.getLikeCount() + 1);
        postRepository.save(post);

        log.debug("User {} liked post {}, new likeCount={}", userId, postId, post.getLikeCount());
        return true;
    }
}
