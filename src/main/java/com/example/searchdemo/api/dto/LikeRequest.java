package com.example.searchdemo.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LikeRequest {

    @NotBlank(message = "userId must not be blank")
    private String userId;
}
