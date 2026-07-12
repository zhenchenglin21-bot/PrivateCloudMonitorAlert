package com.privatecloud.repository;

import com.privatecloud.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {
    List<AlertRule> findByNameNotOrderByUpdatedAtDesc(String name);
}
