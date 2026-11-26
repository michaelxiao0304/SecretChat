package chat.secretchat.admin;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpsAudit {
    static final long RETENTION_MS = Duration.ofDays(30).toMillis();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public OpsAudit(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // Callers supply an action constant and an opaque record ID, never user-submitted material.
    @Transactional
    public void record(String operator, String action, String subject) {
        jdbc.update("INSERT INTO sc_ops.audit_event(operator_name,action_name,subject_id,occurred_ms) VALUES(?,?,?,?)",
            operator, action, subject, clock.millis());
    }

    record Event(long seq, String operator, String action, String subject, long occurredMs) {}

    @Transactional
    public List<Event> history(String operator, long before) {
        record(operator, "AUDIT_LIST", "-");
        return jdbc.query("""
            SELECT seq,operator_name,action_name,subject_id,occurred_ms FROM sc_ops.audit_event
            WHERE seq < ? AND occurred_ms > ? ORDER BY seq DESC LIMIT 50
            """, (rs,n) -> new Event(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)),
            before, clock.millis() - RETENTION_MS);
    }

    @Scheduled(fixedDelayString = "PT1H")
    @Transactional
    public void purgeExpired() {
        long cutoff = clock.millis() - RETENTION_MS;
        jdbc.update("DELETE FROM sc_ops.report_ticket WHERE closed_ms <= ?", cutoff);
        jdbc.update("DELETE FROM sc_ops.audit_event WHERE occurred_ms <= ?", cutoff);
    }
}
