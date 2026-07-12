package com.privatecloud.repository;

import com.privatecloud.entity.MonitorTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MonitorTargetRepository extends JpaRepository<MonitorTarget, Long> {
    Optional<MonitorTarget> findByHost(String host);
}
