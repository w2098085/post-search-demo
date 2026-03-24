package com.example.searchdemo.dto;

import jakarta.validation.constraints.NotBlank;

public record LikeRequest(
        @NotBlank(message = "userId must not be blank")
        String userId
) {}
