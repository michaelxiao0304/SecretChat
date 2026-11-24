package chat.secretchat.admin;

import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
class ProviderFailures {
    private final JdbcTemplate db;
    private final Clock clock;
    ProviderFailures(JdbcTemplate db,Clock clock){this.db=db;this.clock=clock;}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(String provider) {
        db.update("INSERT INTO sc_ops.provider_failure(provider,failed_ms) VALUES (?,?)",provider,clock.millis());
    }

    long recent(String provider) {
        return db.queryForObject("SELECT COUNT(*) FROM sc_ops.provider_failure WHERE provider=? AND failed_ms>?",Long.class,
                provider,cutoff());
    }

    @Scheduled(fixedDelayString="PT1H",initialDelayString="PT1H")
    void purge(){db.update("DELETE FROM sc_ops.provider_failure WHERE failed_ms<=?",cutoff());}
    private long cutoff(){return clock.millis()-Duration.ofHours(24).toMillis();}
}
