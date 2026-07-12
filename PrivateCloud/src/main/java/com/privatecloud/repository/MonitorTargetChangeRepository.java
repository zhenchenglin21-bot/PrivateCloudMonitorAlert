package com.privatecloud.repository;

import com.privatecloud.entity.MonitorTargetChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MonitorTargetChangeRepository extends JpaRepository<MonitorTargetChange, Long> {
    List<MonitorTargetChange> findByHostOrderByChangedAtDesc(String host);
    List<MonitorTargetChange> findByHostInOrderByChangedAtDesc(List<String> hosts);
}
