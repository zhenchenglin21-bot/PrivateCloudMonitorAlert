package com.privatecloud.repository;

import com.privatecloud.entity.AlertFeedbackBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertFeedbackBatchItemRepository extends JpaRepository<AlertFeedbackBatchItem, Long> {
    List<AlertFeedbackBatchItem> findByBatchId(String batchId);
    List<AlertFeedbackBatchItem> findBySourceMessageId(String sourceMessageId);
    List<AlertFeedbackBatchItem> findTop300ByUserIdOrderByCreatedAtDesc(Long userId);
}
