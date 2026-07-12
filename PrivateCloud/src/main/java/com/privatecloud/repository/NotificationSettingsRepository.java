package com.privatecloud.repository;

import com.privatecloud.entity.NotificationSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, Long> {
    List<NotificationSettings> findByEmailEnabledTrue();
}
