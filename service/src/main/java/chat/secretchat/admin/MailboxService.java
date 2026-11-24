package chat.secretchat.admin;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
class MailboxService {
    static final long MAX_TTL = Duration.ofDays(7).toMillis();
    private static final int MAX_CIPHERTEXT = 65535;  // Existing queue uses MySQL BLOB.
    private static final Pattern TOKEN = Pattern.compile("(?:[0-9a-fA-F]{2}){16,256}");
    private static final String CONTENT = """
        SELECT d.envelope_seq, d.envelope_ref, d.message_id, d.sender_account, d.sender_device,
               d.expires_ms, q.ciphertext FROM mailbox_delivery d JOIN queued_envelope q ON q.id=d.envelope_ref
        WHERE d.recipient_device=? AND d.phase='accepted' AND d.expires_ms>?
        """;
    record Submission(String recipientAccountId, String recipientDeviceId, String messageId, long expiresAtMs, String ciphertext) {}
    record Receipt(String reference, long expiresAtMs) {}
    record Envelope(long sequence, String reference, String messageId, String senderAccountId, String senderDeviceId,
                    long expiresAtMs, byte[] ciphertext) {}
    record Page(List<Envelope> envelopes, long through, long nextAfter, boolean done) {}
    record Outcome(String reference, String status, long expiresAtMs) {}
    enum Hint { AVAILABLE, OUTCOME }
    record Signal(String deviceId, Hint kind) {}
    private record Existing(String reference, long expiresAtMs, byte[] digest) {
        Receipt retry(long expires, byte[] hash) {
            if (expires != expiresAtMs || !MessageDigest.isEqual(digest, hash)) throw status(HttpStatus.CONFLICT);
            return new Receipt(reference, expiresAtMs);
        }
    }

    private final JdbcTemplate db;
    private final DirectoryService directory;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    MailboxService(JdbcTemplate db, DirectoryService directory, Clock clock, ApplicationEventPublisher events) {
        this.db = db;
        this.directory = directory;
        this.clock = clock;
        this.events = events;
    }

    Receipt submit(DeviceIdentity sender, Submission request) {
        var recipient = new DeviceIdentity(uuid(request.recipientAccountId()), uuid(request.recipientDeviceId()));
        if (sender.accountId().equals(recipient.accountId())) throw status(HttpStatus.BAD_REQUEST);
        String message = uuid(request.messageId());
        byte[] bytes = ciphertext(request.ciphertext());
        byte[] hash = digest(bytes);
        lockParticipants(sender, recipient);
        directory.requireContact(sender.accountId(), recipient.accountId());
        // The directory row lock serializes sends to a device with its page boundaries, revocation and block changes.
        long now = clock.millis();
        long expires = request.expiresAtMs();
        if (expires <= now || expires > now + MAX_TTL) {
            var old = existing(sender, recipient, message);
            if (old.isEmpty()) throw status(HttpStatus.BAD_REQUEST);
            return old.get(0).retry(expires, hash);
        }
        boolean filtered = directory.isBlocked(recipient.accountId(), sender.accountId());
        String ref = UUID.randomUUID().toString();
        db.update("""
            INSERT INTO mailbox_delivery (envelope_ref,sender_account,sender_device,recipient_account,recipient_device,message_id,
                expires_ms,ciphertext_hash,phase,push_due_ms) VALUES (?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE envelope_seq=envelope_seq
            """, ref, sender.accountId(), sender.deviceId(), recipient.accountId(), recipient.deviceId(), message,
            expires, hash, filtered ? "filtered" : "accepted", filtered ? null : Long.valueOf(now));
        var inserted = existing(sender, recipient, message).get(0);
        if (!ref.equals(inserted.reference())) return inserted.retry(expires, hash);
        if (!filtered) {
            db.update("INSERT INTO queued_envelope (id,device_id,enqueued_ms,expires_ms,ciphertext) VALUES (?,?,?,?,?)",
                ref, recipient.deviceId(), now, expires, bytes);
            events.publishEvent(new Signal(recipient.deviceId(), Hint.AVAILABLE));
        }
        return new Receipt(ref, expires);
    }

    private List<Existing> existing(DeviceIdentity sender, DeviceIdentity recipient, String message) {
        return db.query("""
            SELECT envelope_ref,expires_ms,ciphertext_hash FROM mailbox_delivery
            WHERE recipient_device=? AND sender_device=? AND message_id=? FOR UPDATE
            """, (rs, i) -> new Existing(rs.getString(1), rs.getLong(2), rs.getBytes(3)),
            recipient.deviceId(), sender.deviceId(), message);
    }

    private void lockParticipants(DeviceIdentity sender, DeviceIdentity recipient) {
        boolean senderFirst = sender.accountId().compareTo(recipient.accountId()) < 0;
        if (senderFirst) directory.lockActive(sender);
        try { directory.lockActive(recipient); }
        catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 401) throw status(HttpStatus.CONFLICT);
            throw e;
        }
        if (!senderFirst) directory.lockActive(sender);
    }

    Page page(DeviceIdentity recipient, long after, Long through, int limit) {
        if (after < 0 || (through != null && through < after) || limit < 1 || limit > 100) throw status(HttpStatus.BAD_REQUEST);
        directory.lockActive(recipient);
        long boundary = through == null ? Math.max(after, db.queryForObject("""
            SELECT COALESCE(MAX(envelope_seq),0) FROM mailbox_delivery WHERE recipient_device=? AND phase <> 'filtered'
            """, Long.class, recipient.deviceId())) : through;
        var items = db.query(CONTENT + " AND d.envelope_seq>? AND d.envelope_seq<=? ORDER BY d.envelope_seq LIMIT ?",
            MailboxService::readEnvelope, recipient.deviceId(), clock.millis(), after, boundary, limit + 1);
        boolean done = items.size() <= limit;
        var result = done ? items : items.subList(0, limit);
        return new Page(result, boundary, done ? boundary : result.get(result.size() - 1).sequence(), done);
    }

    Envelope envelope(DeviceIdentity recipient, String ref) {
        directory.lockActive(recipient);
        return db.query(CONTENT + " AND d.envelope_ref=?", MailboxService::readEnvelope,
            recipient.deviceId(), clock.millis(), uuid(ref)).stream().findFirst().orElseThrow(() -> status(HttpStatus.NOT_FOUND));
    }

    private static Envelope readEnvelope(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Envelope(row.getLong(1), row.getString(2), row.getString(3), row.getString(4),
            row.getString(5), row.getLong(6), row.getBytes(7));
    }

    void acknowledge(DeviceIdentity recipient, String reference) {
        String ref = uuid(reference);
        directory.lockActive(recipient);
        record Entry(String phase, long expires, String sender) {}
        var item = db.query("""
            SELECT phase,expires_ms,sender_device FROM mailbox_delivery WHERE recipient_device=? AND envelope_ref=? FOR UPDATE
            """, (rs, i) -> new Entry(rs.getString(1), rs.getLong(2), rs.getString(3)), recipient.deviceId(), ref)
            .stream().findFirst().orElseThrow(() -> status(HttpStatus.NOT_FOUND));
        if (item.phase().equals("received")) return;
        if (item.phase().equals("filtered")) throw status(HttpStatus.NOT_FOUND);
        if (item.expires() <= clock.millis()) throw status(HttpStatus.GONE);
        db.update("UPDATE mailbox_delivery SET phase='received', push_due_ms=NULL WHERE envelope_ref=?", ref);
        db.update("DELETE FROM queued_envelope WHERE id=?", ref);
        events.publishEvent(new Signal(item.sender(), Hint.OUTCOME));
    }

    Outcome outcome(DeviceIdentity sender, String reference) {
        String ref = uuid(reference);
        directory.lockActive(sender);
        return db.query("""
            SELECT phase,expires_ms FROM mailbox_delivery WHERE sender_device=? AND envelope_ref=?
            """, (rs, i) -> {
                long expires = rs.getLong(2);
                String visible = "received".equals(rs.getString(1)) ? "received" : expires <= clock.millis() ? "expired" : "accepted";
                return new Outcome(ref, visible, expires);
            }, sender.deviceId(), ref).stream().findFirst().orElseThrow(() -> status(HttpStatus.NOT_FOUND));
    }

    void bind(DeviceIdentity device, String token) {
        directory.lockActive(device);
        if (token == null || !TOKEN.matcher(token).matches()) throw status(HttpStatus.BAD_REQUEST);
        String canonical = token.toLowerCase(Locale.ROOT);
        db.update("DELETE FROM mailbox_push_binding WHERE device_id=? OR apns_token=?", device.deviceId(), canonical);
        db.update("INSERT INTO mailbox_push_binding (device_id,apns_token) VALUES (?,?)", device.deviceId(), canonical);
    }

    void unbind(DeviceIdentity device) {
        directory.lockActive(device);
        db.update("DELETE FROM mailbox_push_binding WHERE device_id=?", device.deviceId());
    }

    @EventListener
    void retire(DeviceRetired retired) {
        String device = retired.deviceId();
        // Take ledger locks before queue rows, the same order used by acknowledgements and expiry.
        db.update("UPDATE mailbox_delivery SET push_due_ms=NULL WHERE recipient_device=? OR sender_device=?", device, device);
        db.update("""
            DELETE FROM queued_envelope WHERE device_id=? OR id IN
                (SELECT envelope_ref FROM mailbox_delivery WHERE sender_device=?)
            """, device, device);
        db.update("DELETE FROM mailbox_delivery WHERE recipient_device=? OR sender_device=?", device, device);
        db.update("DELETE FROM mailbox_push_binding WHERE device_id=?", device);
    }

    private static String uuid(String value) {
        try { if (value != null && UUID.fromString(value).toString().equals(value)) return value; }
        catch (IllegalArgumentException ignored) { }
        throw status(HttpStatus.BAD_REQUEST);
    }
    private static byte[] ciphertext(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > ((MAX_CIPHERTEXT + 2) / 3) * 4) throw status(HttpStatus.BAD_REQUEST);
        try { return Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException ex) { throw status(HttpStatus.BAD_REQUEST); }
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static ResponseStatusException status(HttpStatus status) { return new ResponseStatusException(status); }
}
