package com.axnavigator.recovery;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A healthy HTTP process must not hide a missing recovery migration. */
@Component
@ConditionalOnProperty(name="ax.recovery.enabled", havingValue="true")
public class RecoveryStartupCheck {
    private final JdbcTemplate jdbc;
    public RecoveryStartupCheck(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @PostConstruct public void validateSchema() {
        jdbc.queryForObject("SELECT count(*) FROM ai_commands WHERE false", Long.class);
        jdbc.queryForObject("SELECT count(*) FROM ai_command_inputs WHERE false", Long.class);
    }
}
