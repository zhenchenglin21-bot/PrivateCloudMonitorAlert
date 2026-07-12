package com.privatecloud.repository;

import com.privatecloud.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {
    @EntityGraph(attributePaths = {"roles", "servers"})
    List<User> findAll();

    @EntityGraph(attributePaths = {"roles", "servers"})
    Optional<User> findByUsername(String username);

    @Override
    @EntityGraph(attributePaths = {"roles", "servers"})
    Optional<User> findById(Long id);

    boolean existsByUsername(String username);
}
