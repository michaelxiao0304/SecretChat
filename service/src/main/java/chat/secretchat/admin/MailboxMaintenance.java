package chat.secretchat.admin;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final Batch expiryBatch = new Batch("expiry");
    private final Batch pushBatch = new Batch("push");
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
    void expire() { expiryBatch.run(this::expireDue); }

    private void expireDue() {
        long now = clock.millis();
        var due = db.queryForList("""
            SELECT envelope_ref FROM mailbox_delivery WHERE phase IN ('accepted','filtered') AND expires_ms<=?
            ORDER BY expires_ms LIMIT 200
            """, String.class, now);
        int expired = 0;
        int ciphertexts = 0;
        for (String ref : due) {
            var removal = tx.execute(s -> expire(ref, now));
            expired += removal.expired();
            ciphertexts += removal.ciphertexts();
        }
        // Historical queue rows that predate the mailbox have no corresponding delivery record.
        ciphertexts += db.update("DELETE FROM queued_envelope WHERE expires_ms<=? LIMIT 500", now);
        int receipts = db.update("DELETE FROM mailbox_delivery WHERE phase IN ('received','expired') AND expires_ms<=? LIMIT 500",
            now - MailboxService.MAX_TTL);
        if (expired + ciphertexts + receipts > 0) {
            log.info("event=mailbox_cleanup expired={} ciphertextDeleted={} receiptsDeleted={}", expired, ciphertexts, receipts);
        }
    }

    private record Removal(int expired, int ciphertexts) {}

    private Removal expire(String ref, long now) {
        int changed = db.update("""
            UPDATE mailbox_delivery SET phase='expired', push_due_ms=NULL WHERE envelope_ref=?
                AND expires_ms<=? AND phase IN ('accepted','filtered')
            """, ref, now);
        if (changed == 0) return new Removal(0, 0);
        int ciphertexts = db.update("DELETE FROM queued_envelope WHERE id=?", ref);
        String sender = db.queryForObject("SELECT sender_device FROM mailbox_delivery WHERE envelope_ref=?", String.class, ref);
        events.publishEvent(new MailboxService.Signal(sender, MailboxService.Hint.OUTCOME));
        return new Removal(changed, ciphertexts);
    }

    @Scheduled(fixedDelay=500, initialDelayString="${secretchat.mailbox.initial-delay-ms:1000}")
    void push() { pushBatch.run(this::sendDue); }

    private void sendDue() {
        var due = db.query("""
            SELECT envelope_ref,recipient_account,recipient_device,push_attempts FROM mailbox_delivery
            WHERE push_due_ms<=? ORDER BY push_due_ms LIMIT 32
            """, (rs, i) -> new Pending(rs.getString(1), new DeviceIdentity(rs.getString(2), rs.getString(3)), rs.getInt(4)), clock.millis());
        for (Pending item : due) process(item);
    }

    /** Each fixed-delay job has its own backoff, so a push outage cannot stall expiry. */
    private static final class Batch {
        private final String name;
        private long retryAfterNanos;

        Batch(String name) { this.name = name; }

        void run(Runnable task) {
            if (retryAfterNanos != 0 && System.nanoTime() - retryAfterNanos < 0) return;
            try { task.run(); }
            catch (RuntimeException failure) {
                retryAfterNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                log.warn("event=mailbox_job_failure job={} retryInMs=30000", name);
            }
        }
    }

    private record Pending(String ref, DeviceIdentity recipient, int previous) {}
    private record Attempt(String sender, int previous, long expires) {}
    private record Retry(String reason, int attempt, Long delayMs) {
        void report() {
            if (delayMs == null) log.warn("event=push_exhausted reason={} attempts={}", reason, attempt);
            else log.info("event=push_retry reason={} attempt={} delayMs={}", reason, attempt, delayMs);
        }
    }

    private void process(Pending item) {
        Retry retry;
        try { retry = tx.execute(s -> push(item)); }
        catch (ResponseStatusException retired) {
            if (retired.getStatusCode().value() != 401) throw retired;
            // A directory restored independently of this unbacked queue can leave an obsolete pending job.
            db.update("UPDATE mailbox_delivery SET push_due_ms=NULL WHERE envelope_ref=?", item.ref());
            return;
        } catch (RuntimeException failure) {
            // A failed transaction releases its claim. Only reschedule if another worker hasn't advanced it.
            retry = retry(item.ref(), item.previous(), 30000, "worker");
        }
        if (retry != null) retry.report();
    }

    private Retry retry(String ref, int previous, long delayMs, String reason) {
        int attempt = previous + 1;
        Long next = attempt < MAX_PUSH_ATTEMPTS ? clock.millis() + delayMs : null;
        int changed = db.update("""
            UPDATE mailbox_delivery SET push_due_ms=?, push_attempts=push_attempts+1
            WHERE envelope_ref=? AND push_attempts=? AND push_due_ms<=?
            """, next, ref, previous, clock.millis());
        return changed == 0 ? null : new Retry(reason, attempt, next == null ? null : delayMs);
    }

    private Retry push(Pending item) {
        directory.lockActive(item.recipient());
        var rows = db.query("""
            SELECT sender_account,push_attempts,expires_ms FROM mailbox_delivery
            WHERE envelope_ref=? AND push_due_ms<=? AND phase='accepted' FOR UPDATE
            """, (rs, i) -> new Attempt(rs.getString(1), rs.getInt(2), rs.getLong(3)), item.ref(), clock.millis());
        if (rows.isEmpty()) return null;
        var attempt = rows.get(0);
        String device = item.recipient().deviceId();
        var tokens = db.queryForList("SELECT apns_token FROM mailbox_push_binding WHERE device_id=? FOR UPDATE", String.class, device);
        if (!tokens.isEmpty() && attempt.expires() > clock.millis() && !directory.isBlocked(item.recipient().accountId(), attempt.sender())) {
            String token = tokens.get(0);
            var result = providers.sendPush(token, item.ref());
            if (result == DeliveryProviders.PushResult.INVALID_TOKEN) {
                db.update("DELETE FROM mailbox_push_binding WHERE device_id=? AND apns_token=?", device, token);
            }
            if (result == DeliveryProviders.PushResult.RETRY) {
                return retry(item.ref(), attempt.previous(), 1000L << (2 * attempt.previous()), "provider");
            }
        }
        db.update("UPDATE mailbox_delivery SET push_due_ms=NULL, push_attempts=push_attempts+1 WHERE envelope_ref=?", item.ref());
        return null;
    }
}
