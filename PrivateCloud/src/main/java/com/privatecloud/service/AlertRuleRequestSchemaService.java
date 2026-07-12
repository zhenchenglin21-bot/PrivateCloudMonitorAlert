package com.privatecloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class AlertRuleRequestSchemaService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleRequestSchemaService.class);

    private final JdbcTemplate jdbcTemplate;

    public AlertRuleRequestSchemaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        relaxRuleIdNullable();
        ensureRuleForeignKeyUsesSetNull();
    }

    private void relaxRuleIdNullable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                            "WHERE table_schema = DATABASE() AND table_name = 'alert_rule_requests'",
                    Integer.class
            );
            if (count == null || count == 0) {
                return;
            }
            String nullable = jdbcTemplate.queryForObject(
                    "SELECT IS_NULLABLE FROM information_schema.columns " +
                            "WHERE table_schema = DATABASE() AND table_name = 'alert_rule_requests' AND column_name = 'rule_id'",
                    String.class
            );
            if (nullable != null && "NO".equals(nullable.toUpperCase(Locale.ROOT))) {
                jdbcTemplate.execute("ALTER TABLE alert_rule_requests MODIFY COLUMN rule_id BIGINT NULL");
                log.info("Adjusted schema: alert_rule_requests.rule_id changed to nullable.");
            }
        } catch (Exception ex) {
            log.warn("Skip schema adjustment for alert_rule_requests.rule_id: {}", ex.getMessage());
        }
    }

    private void ensureRuleForeignKeyUsesSetNull() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.referential_constraints " +
                            "WHERE constraint_schema = DATABASE() AND table_name = 'alert_rule_requests' " +
                            "AND referenced_table_name = 'alert_rules'",
                    Integer.class
            );
            if (count == null || count == 0) {
                return;
            }

            String deleteRule = jdbcTemplate.queryForObject(
                    "SELECT DELETE_RULE FROM information_schema.referential_constraints " +
                            "WHERE constraint_schema = DATABASE() AND table_name = 'alert_rule_requests' " +
                            "AND referenced_table_name = 'alert_rules' LIMIT 1",
                    String.class
            );
            if (deleteRule != null && "SET NULL".equalsIgnoreCase(deleteRule)) {
                return;
            }

            String constraintName = jdbcTemplate.queryForObject(
                    "SELECT CONSTRAINT_NAME FROM information_schema.referential_constraints " +
                            "WHERE constraint_schema = DATABASE() AND table_name = 'alert_rule_requests' " +
                            "AND referenced_table_name = 'alert_rules' LIMIT 1",
                    String.class
            );
            if (constraintName == null || constraintName.isBlank()) {
                return;
            }

            jdbcTemplate.execute("ALTER TABLE alert_rule_requests DROP FOREIGN KEY `" + constraintName + "`");
            jdbcTemplate.execute(
                    "ALTER TABLE alert_rule_requests " +
                            "ADD CONSTRAINT `" + constraintName + "` " +
                            "FOREIGN KEY (rule_id) REFERENCES alert_rules(id) ON DELETE SET NULL"
            );
            log.info("Adjusted schema: alert_rule_requests.rule_id foreign key now uses ON DELETE SET NULL.");
        } catch (Exception ex) {
            log.warn("Skip foreign key adjustment for alert_rule_requests.rule_id: {}", ex.getMessage());
        }
    }
}
