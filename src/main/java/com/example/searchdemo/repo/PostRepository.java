package com.example.searchdemo.repo;

import com.example.searchdemo.domain.Post;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostRepository extends JpaRepository<Post, String> {
}
