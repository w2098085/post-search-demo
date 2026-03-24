package com.example.searchdemo.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostResponse {

    private String id;
    private String content;
    private Instant createdAt;
    private long likeCount;
}
