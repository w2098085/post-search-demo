package com.example.searchdemo.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreatePostRequest {

    @NotBlank(message = "content must not be blank")
    @Size(max = 5000, message = "content must not exceed 5000 characters")
    private String content;
}
