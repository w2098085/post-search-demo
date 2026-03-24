package com.example.searchdemo.service;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class Tokenizer {

    /**
     * Tokenizes content into sorted, unique, lowercase alphanumeric tokens.
     */
    public List<String> tokenize(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        return Arrays.stream(content.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> !t.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }
}
