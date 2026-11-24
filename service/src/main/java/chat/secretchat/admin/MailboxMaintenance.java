package chat.secretchat.admin;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class MailboxMaintenance {
    private static final int MAX_PUSH_ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(MailboxMaintenance.class);
    private final DirectoryService directory;
    private long retryDatabaseAfterNanos;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final DeliveryProviders providers;
    private final Clock clock;

    MailboxMaintenance(JdbcTemplate db, TransactionTemplate tx, ApplicationEventPublisher events,
                       DeliveryProviders providers, Clock clock, DirectoryService directory) {
        this.directory = directory;
        this.db = db;
        this.tx = tx;
        this.events = events;
        this.providers = providers;
        this.clock = clock;
    }

    @Scheduled(fixedDelay=1000, initialDelayString="${secretchat.mailbox.initial-delay-ms:1000}")
    void expire() {
        long now = clock.millis();
        var due = db.queryForList("""
            SELECT envelope_ref FROM mailbox_delivery WHERE phase IN ('accepted','filtered') AND expires_ms<=?
            ORDER BY expires_ms LIMIT 200
            """, String.class, now);
        for (String ref : due) tx.executeWithoutResult(s -> expire(ref, now));
        // Historical queue rows that predate the mailbox have no corresponding delivery record.
        db.update("DELETE FROM queued_envelope WHERE expires_ms<=? LIMIT 500", now);
        db.update("DELETE FROM mailbox_delivery WHERE phase IN ('received','expired') AND expires_ms<=? LIMIT 500",
            now - MailboxService.MAX_TTL);
    }

    private void expire(String ref, long now) {
        int changed = db.update("""
            UPDATE mailbox_delivery SET phase='expired', push_due_ms=NULL WHERE envelope_ref=?
                AND expires_ms<=? AND phase IN ('accepted','filtered')
            """, ref, now);
        if (changed == 0) return;
        db.update("DELETE FROM queued_envelope WHERE id=?", ref);
        String sender = db.queryForObject("SELECT sender_device FROM mailbox_delivery WHERE envelope_ref=?", String.class, ref);
        events.publishEvent(new MailboxService.Signal(sender, MailboxService.Hint.OUTCOME));
    }

    @Scheduled(fixedDelay=500, initialDelayString="${secretchat.mailbox.initial-delay-ms:1000}")
    void push() {
        if (retryDatabaseAfterNanos != 0 && System.nanoTime() - retryDatabaseAfterNanos < 0) return;
        try {
            var due = db.query("""
                SELECT envelope_ref,recipient_account,recipient_device FROM mailbox_delivery
                WHERE push_due_ms<=? ORDER BY push_due_ms LIMIT 32
                """, (rs, i) -> new Pending(rs.getString(1), new DeviceIdentity(rs.getString(2), rs.getString(3))), clock.millis());
            for (Pending item : due) process(item);
        } catch (DataAccessException unavailable) {
            retryDatabaseAfterNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            log.warn("Mailbox push database unavailable. Retrying in 30 seconds.");
        }
    }

    private record Pending(String ref, DeviceIdentity recipient) {}
    private record Attempt(String sender, int previous, long expires) {}

    private void process(Pending item) {
        try { tx.executeWithoutResult(s -> push(item)); }
        catch (ResponseStatusException retired) {
            if (retired.getStatusCode().value() != 401) throw retired;
            // A directory restored independently of this unbacked queue can leave an obsolete pending job.
            db.update("UPDATE mailbox_delivery SET push_due_ms=NULL WHERE envelope_ref=?", item.ref());
        } catch (RuntimeException failure) {
            // A failed transaction releases its claim. Give other jobs a turn and bound failures on this row.
            db.update("""
                UPDATE mailbox_delivery SET push_due_ms=CASE WHEN push_attempts>=? THEN NULL ELSE ? END,
                    push_attempts=push_attempts+1 WHERE envelope_ref=? AND push_due_ms<=?
                """, MAX_PUSH_ATTEMPTS - 1, clock.millis() + 30000, item.ref(), clock.millis());
            log.warn("Mailbox push attempt failed");
        }
    }

    private void push(Pending item) {
        directory.lockActive(item.recipient());
        var rows = db.query("""
            SELECT sender_account,push_attempts,expires_ms FROM mailbox_delivery
            WHERE envelope_ref=? AND push_due_ms<=? AND phase='accepted' FOR UPDATE
            """, (rs, i) -> new Attempt(rs.getString(1), rs.getInt(2), rs.getLong(3)), item.ref(), clock.millis());
        if (rows.isEmpty()) return;
        var attempt = rows.get(0);
        String device = item.recipient().deviceId();
        var tokens = db.queryForList("SELECT apns_token FROM mailbox_push_binding WHERE device_id=? FOR UPDATE", String.class, device);
        Long next = null;
        if (!tokens.isEmpty() && attempt.expires() > clock.millis() && !directory.isBlocked(item.recipient().accountId(), attempt.sender())) {
            String token = tokens.get(0);
            var result = providers.sendPush(token, item.ref());
            if (result == DeliveryProviders.PushResult.INVALID_TOKEN) {
                db.update("DELETE FROM mailbox_push_binding WHERE device_id=? AND apns_token=?", device, token);
            }
            if (result == DeliveryProviders.PushResult.RETRY && attempt.previous() + 1 < MAX_PUSH_ATTEMPTS) {
                next = clock.millis() + (1000L << (2 * attempt.previous()));
            }
        }
        db.update("UPDATE mailbox_delivery SET push_due_ms=?, push_attempts=push_attempts+1 WHERE envelope_ref=?", next, item.ref());
    }
}
