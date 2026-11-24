package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MailboxMaintenanceTest {
    @Test void batchFailureLogsOncePerJobDuringBackoffAndDoesNotExposeTheException() {
        var db = mock(JdbcTemplate.class, inv -> { throw new IllegalStateException("private SQL or bound value"); });
        var jobs = new MailboxMaintenance(db, null, null, null, Clock.systemUTC(), null);
        try (var logs = new DeliveryLogCapture(MailboxMaintenance.class)) {
            jobs.expire();
            jobs.push();
            jobs.expire();
            jobs.push();
            assertThat(logs.messages()).containsExactly("event=mailbox_job_failure job=expiry retryInMs=30000",
                "event=mailbox_job_failure job=push retryInMs=30000");
        }
    }
}
