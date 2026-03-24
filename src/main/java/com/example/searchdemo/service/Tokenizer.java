package com.example.searchdemo.service;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Simple tokenizer: lowercase, keep only alphanumeric characters, split on whitespace.
 * No fuzzy matching, no stemming.
 */
@Component
public class Tokenizer {

    public List<String> tokenize(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return Arrays.stream(content.toLowerCase().split("\\s+"))
                .map(token -> token.replaceAll("[^a-z0-9]", ""))
                .filter(token -> !token.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }
}
