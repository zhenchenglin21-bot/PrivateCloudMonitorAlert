package com.privatecloud.repository;

import com.privatecloud.entity.AlertRuleRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRuleRequestRepository extends JpaRepository<AlertRuleRequest, Long> {
    List<AlertRuleRequest> findByStatus(String status);
    List<AlertRuleRequest> findByStatusOrderByRequestedAtDesc(String status);
    List<AlertRuleRequest> findByRuleId(Long ruleId);
    List<AlertRuleRequest> findByActionOrderByRequestedAtDesc(String action);
    List<AlertRuleRequest> findByHostAndStatusOrderByRequestedAtDesc(String host, String status);
    Optional<AlertRuleRequest> findFirstByRuleIdAndHostAndStatusOrderByRequestedAtDesc(Long ruleId, String host, String status);
    Optional<AlertRuleRequest> findFirstByRuleIdAndActionAndStatusOrderByRequestedAtDesc(Long ruleId, String action, String status);
    Optional<AlertRuleRequest> findFirstByRuleIdAndActionOrderByRequestedAtDesc(Long ruleId, String action);
    List<AlertRuleRequest> findByRequestedBy(Long requestedBy);
    List<AlertRuleRequest> findByRequestedByOrderByRequestedAtDesc(Long requestedBy);
}
