package com.example.searchdemo.dto;

import java.util.List;

public record SearchResponse(
        List<PostResponse> results,
        int total
) {}
