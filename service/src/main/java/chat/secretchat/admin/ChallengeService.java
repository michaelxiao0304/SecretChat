package chat.secretchat.admin;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class ChallengeService {
    private static final long TEN_MINUTES = Duration.ofMinutes(10).toMillis();
    private static final long ONE_HOUR = Duration.ofHours(1).toMillis();
    private final JdbcTemplate db;
    private final DeliveryProviders providers;
    private final Clock clock;
    private final SecretKeySpec secret;
    private final TransactionTemplate transaction;
    final boolean unverifiedRegistration;

    ChallengeService(JdbcTemplate db, DeliveryProviders providers, Clock clock, PlatformTransactionManager tx,
        @Value("${secretchat.directory.challenge-secret:${SECRETCHAT_CHALLENGE_SECRET:}}") String configuredSecret,
        @Value("${secretchat.registration.unverified:${SECRETCHAT_REGISTRATION_UNVERIFIED:false}}") boolean unverified,
        Environment environment) {
        if (configuredSecret.isBlank() && environment.acceptsProfiles(Profiles.of("local"))) {
            configuredSecret = DirectorySecrets.token();
        }
        byte[] key = configuredSecret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalArgumentException("Configure SECRETCHAT_CHALLENGE_SECRET with at least 32 random bytes");
        this.secret = new SecretKeySpec(key, "HmacSHA256");
        this.db = db;
        this.clock = clock;
        this.providers = providers;
        this.transaction = new TransactionTemplate(tx);
        this.unverifiedRegistration = unverified;
    }

    record Ticket(String challengeId) {}
    record Verification(String email, String purpose, byte[] mac, long expires, int attempts, String accountId) {
        boolean validFor(String expected, long now) {
            return purpose.equals(expected) && expires > now && attempts > 0
                && (!purpose.equals("recover") || accountId != null);
        }
    }

    Ticket issue(String input, String purpose, String remoteAddress) {
        if (!"recover".equals(purpose) && !("register".equals(purpose) && !unverifiedRegistration)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported verification purpose");
        }
        String email = normalize(input);
        if (!email.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email address");
        }
        String id = UUID.randomUUID().toString();
        String code = String.format(Locale.ROOT, "%06d", DirectorySecrets.RANDOM.nextInt(1_000_000));
        transaction.executeWithoutResult(status -> {
            long now = clock.millis();
            throttle("address:" + remoteAddress, 20, now);
            throttle("email:" + email, 3, now);
            String account = db.queryForList("SELECT account_id FROM sc_directory.accounts WHERE email=?", String.class, email)
                .stream().findFirst().orElse(null);
            // Only the most recent challenge is valid across both purposes.
            db.update("DELETE FROM sc_directory.email_challenges WHERE email=?", email);
            db.update("""
                INSERT INTO sc_directory.email_challenges(challenge_id,email,purpose,code_mac,expires_ms,attempts,account_id)
                VALUES (?,?,?,?,?,5,?)
                """, id, email, purpose, mac(id + ":" + code), now + TEN_MINUTES, account);
        });
        try {
            providers.sendEmail(email, code);
        } catch (RuntimeException failure) {
            db.update("DELETE FROM sc_directory.email_challenges WHERE challenge_id=?", id);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Verification email unavailable");
        }
        return new Ticket(id);
    }

    static String normalize(String input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String value = input.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > 254 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid account identifier");
        }
        return value;
    }

    private void throttle(String scope, int limit, long now) {
        String bucket = HexFormat.of().formatHex(mac(scope));
        db.update("""
            INSERT INTO sc_directory.challenge_throttle(bucket_key,expires_ms,uses_count) VALUES (?,?,0)
            ON DUPLICATE KEY UPDATE bucket_key=VALUES(bucket_key)
            """, bucket, now + ONE_HOUR);
        int updated = db.update("""
            UPDATE sc_directory.challenge_throttle
               SET uses_count=CASE WHEN expires_ms<=? THEN 1 ELSE uses_count+1 END,
                   expires_ms=CASE WHEN expires_ms<=? THEN ? ELSE expires_ms END
             WHERE bucket_key=? AND (expires_ms<=? OR uses_count<?)
            """, now, now, now + ONE_HOUR, bucket, now, limit);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** Caller commits InvalidCode so a rejected attempt remains counted. */
    Verification consume(String id, String code, String purpose) {
        if (id == null || id.length() != 36 || code == null || code.length() != 6) throw new InvalidCode();
        var rows = db.query("""
            SELECT email,purpose,code_mac,expires_ms,attempts,account_id FROM sc_directory.email_challenges
            WHERE challenge_id=? FOR UPDATE
            """, (rs, n) -> new Verification(rs.getString(1), rs.getString(2), rs.getBytes(3),
                rs.getLong(4), rs.getInt(5), rs.getString(6)), id);
        if (rows.isEmpty()) throw new InvalidCode();
        var verification = rows.get(0);
        if (!verification.validFor(purpose, clock.millis())) throw new InvalidCode();
        if (!MessageDigest.isEqual(verification.mac(), mac(id + ":" + code))) {
            db.update("UPDATE sc_directory.email_challenges SET attempts=attempts-1 WHERE challenge_id=?", id);
            throw new InvalidCode();
        }
        db.update("DELETE FROM sc_directory.email_challenges WHERE challenge_id=?", id);
        return verification;
    }

    @Scheduled(fixedDelayString="${secretchat.directory.challenge-cleanup-ms:600000}")
    void purgeExpired() {
        db.update("DELETE FROM sc_directory.email_challenges WHERE expires_ms<=?", clock.millis());
        db.update("DELETE FROM sc_directory.challenge_throttle WHERE expires_ms<=?", clock.millis());
    }

    private byte[] mac(String value) {
        try {
            var hmac = Mac.getInstance("HmacSHA256");
            hmac.init(secret);
            return hmac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class InvalidCode extends ResponseStatusException {
        InvalidCode() { super(HttpStatus.UNAUTHORIZED, "Verification failed"); }
    }
}
