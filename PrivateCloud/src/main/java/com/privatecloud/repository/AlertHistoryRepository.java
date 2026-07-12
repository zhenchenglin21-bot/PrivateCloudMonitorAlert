package com.privatecloud.repository;

import com.privatecloud.entity.AlertHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AlertHistoryRepository extends JpaRepository<AlertHistory, Long>, JpaSpecificationExecutor<AlertHistory> {
    List<AlertHistory> findByHostIn(List<String> hosts);
    List<AlertHistory> findByHost(String host);
    Optional<AlertHistory> findFirstByFingerprintAndStatusNotOrderByOccurredAtDesc(String fingerprint, String status);
    List<AlertHistory> findByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDesc(Instant start, Instant end);
    List<AlertHistory> findByOccurredAtGreaterThanEqualAndOccurredAtLessThanAndHostInOrderByOccurredAtDesc(
            Instant start,
            Instant end,
            List<String> hosts
    );
}
