package com.example.searchdemo;

import com.example.searchdemo.api.dto.CreatePostRequest;
import com.example.searchdemo.api.dto.LikeRequest;
import com.example.searchdemo.api.dto.PostResponse;
import com.example.searchdemo.api.dto.SearchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostSearchIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String base() {
        return "http://localhost:" + port + "/api";
    }

    @Test
    void createPost_returnsCreatedPost() {
        CreatePostRequest req = new CreatePostRequest();
        req.setContent("Hello Spring Boot world");

        ResponseEntity<PostResponse> response = restTemplate.postForEntity(
                base() + "/posts", req, PostResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).isEqualTo("Hello Spring Boot world");
        assertThat(response.getBody().getId()).isNotBlank();
        assertThat(response.getBody().getLikeCount()).isZero();
    }

    @Test
    void likePost_idempotent() {
        // Create a post first
        CreatePostRequest createReq = new CreatePostRequest();
        createReq.setContent("Idempotent like test post");
        PostResponse post = restTemplate.postForEntity(
                base() + "/posts", createReq, PostResponse.class).getBody();
        assertThat(post).isNotNull();

        LikeRequest likeReq = new LikeRequest();
        likeReq.setUserId("user-like-test");

        // First like succeeds
        var r1 = restTemplate.postForEntity(
                base() + "/posts/" + post.getId() + "/like", likeReq, String.class);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r1.getBody()).contains("true");

        // Second like is idempotent
        var r2 = restTemplate.postForEntity(
                base() + "/posts/" + post.getId() + "/like", likeReq, String.class);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r2.getBody()).contains("false");
    }

    @Test
    void search_findsPostsByTerm() {
        // Create posts
        CreatePostRequest req1 = new CreatePostRequest();
        req1.setContent("Java concurrency patterns advanced");
        restTemplate.postForEntity(base() + "/posts", req1, PostResponse.class);

        CreatePostRequest req2 = new CreatePostRequest();
        req2.setContent("Spring Boot microservices architecture");
        restTemplate.postForEntity(base() + "/posts", req2, PostResponse.class);

        // Search for "java" should find req1
        ResponseEntity<SearchResponse> searchResp = restTemplate.getForEntity(
                base() + "/search?q=java", SearchResponse.class);
        assertThat(searchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(searchResp.getBody()).isNotNull();
        assertThat(searchResp.getBody().getResults())
                .anyMatch(p -> p.getContent().contains("Java"));
    }

    @Test
    void search_andLogic_requiresAllTerms() {
        CreatePostRequest req1 = new CreatePostRequest();
        req1.setContent("unique search term alpha beta");
        restTemplate.postForEntity(base() + "/posts", req1, PostResponse.class);

        CreatePostRequest req2 = new CreatePostRequest();
        req2.setContent("unique search term alpha only");
        restTemplate.postForEntity(base() + "/posts", req2, PostResponse.class);

        // Search for "alpha beta" should only match req1
        ResponseEntity<SearchResponse> resp = restTemplate.getForEntity(
                base() + "/search?q=alpha+beta", SearchResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getResults())
                .allMatch(p -> p.getContent().contains("beta"));
    }
}
