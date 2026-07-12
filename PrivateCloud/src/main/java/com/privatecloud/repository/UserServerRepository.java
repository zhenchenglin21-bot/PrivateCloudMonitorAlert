package com.privatecloud.repository;

import com.privatecloud.entity.UserServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserServerRepository extends JpaRepository<UserServer, Long> {
    List<UserServer> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}
