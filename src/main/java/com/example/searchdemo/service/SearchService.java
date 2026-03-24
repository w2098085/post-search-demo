package com.example.searchdemo.service;

import com.example.searchdemo.domain.Post;
import com.example.searchdemo.repo.PostRepository;
import com.example.searchdemo.repo.PostTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final PostTermRepository postTermRepository;
    private final PostRepository postRepository;
    private final Tokenizer tokenizer;

    /**
     * Search posts containing ALL terms in the query.
     *
     * @param query  the search query string
     * @param sortBy "recent" (default) or "likes"
     * @param limit  max results to return
     * @param offset number of results to skip
     * @return paginated list of matching posts
     */
    @Transactional(readOnly = true)
    public List<Post> search(String query, String sortBy, int limit, int offset) {
        List<String> terms = tokenizer.tokenize(query);
        if (terms.isEmpty()) {
            log.debug("Empty query after tokenization, returning empty results");
            return List.of();
        }

        List<String> matchingPostIds = postTermRepository
                .findPostIdsContainingAllTerms(terms, terms.size());

        if (matchingPostIds.isEmpty()) {
            return List.of();
        }

        List<Post> posts = postRepository.findAllById(matchingPostIds);

        Comparator<Post> comparator = "likes".equalsIgnoreCase(sortBy)
                ? Comparator.comparingLong(Post::getLikeCount).reversed()
                : Comparator.comparing(Post::getCreatedAt).reversed();

        return posts.stream()
                .sorted(comparator)
                .skip(offset)
                .limit(limit)
                .toList();
    }
}
