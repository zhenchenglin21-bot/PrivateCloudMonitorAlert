package com.privatecloud.repository;

import com.privatecloud.entity.AlertRuleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRuleAssignmentRepository extends JpaRepository<AlertRuleAssignment, Long> {
    Optional<AlertRuleAssignment> findByRuleIdAndHost(Long ruleId, String host);
    List<AlertRuleAssignment> findByHost(String host);
    List<AlertRuleAssignment> findByRuleId(Long ruleId);
}
