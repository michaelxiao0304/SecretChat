package chat.secretchat.admin;

import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class QueueController {
    private final JdbcClient database;
    private final Clock clock;

    QueueController(JdbcClient database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    @GetMapping("/admin-api/queue")
    QueueSnapshot queue() {
        long now = clock.millis();
        return database.sql("""
                SELECT COUNT(*) AS depth, MIN(enqueued_ms) AS oldest
                FROM queued_envelope WHERE expires_ms > :now
                """)
            .param("now", now)
            .query((row, index) -> QueueSnapshot.at(row.getLong("depth"), row.getObject("oldest", Long.class), now))
            .single();
    }
}
