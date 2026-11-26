package chat.secretchat.admin;

import static org.springframework.http.HttpStatus.*;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DirectoryService {
    record AccountRequest(String email, String challengeId, String code, String inviteCode,
                          String identityKey, String signedPrekey, String protocolPrekeys) {}
    record Access(String accountId, String deviceId, String token) {}
    record Contact(String accountId, String deviceId, String identityKey, boolean blocked) {}
    record Keys(byte[] identity, byte[] signed, byte[] protocol) {
        static Keys from(AccountRequest r) {
            return new Keys(decode(r.identityKey()), decode(r.signedPrekey()),
                r.protocolPrekeys() == null ? null : decode(r.protocolPrekeys()));
        }
    }

    private final JdbcTemplate db;
    private final Clock clock;
    private final ChallengeService challenges;
    private final DirectoryInvites invites;
    private final ApplicationEventPublisher events;
    DirectoryService(JdbcTemplate db, Clock clock, ChallengeService challenges, DirectoryInvites invites,
                     ApplicationEventPublisher events) {
        this.db = db;
        this.clock = clock;
        this.challenges = challenges;
        this.invites = invites;
        this.events = events;
    }

    public DeviceIdentity requireDevice(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new ResponseStatusException(UNAUTHORIZED);
        String token = authorization.substring(7);
        if (!DirectorySecrets.validToken(token)) throw new ResponseStatusException(UNAUTHORIZED);
        var rows = db.query("SELECT account_id,device_id FROM sc_directory.devices WHERE token_hash=?",
            (rs, n) -> new DeviceIdentity(rs.getString(1), rs.getString(2)), DirectorySecrets.digest(token));
        if (rows.isEmpty()) throw new ResponseStatusException(UNAUTHORIZED);
        return rows.get(0);
    }

    /** Caller owns the transaction, including any destination-device checks and mailbox mutations. */
    void lockActive(DeviceIdentity device) {
        lockAccount(device.accountId(), UNAUTHORIZED);
        if (!activeDevice(device.accountId()).contains(device.deviceId())) throw new ResponseStatusException(UNAUTHORIZED);
    }

    private List<String> activeDevice(String account) {
        return db.queryForList("SELECT device_id FROM sc_directory.devices WHERE account_id=? FOR UPDATE", String.class, account);
    }

    public String deviceForAccount(String account) {
        var devices = db.queryForList("SELECT device_id FROM sc_directory.devices WHERE account_id=?", String.class, account);
        if (devices.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
        return devices.get(0);
    }

    private void lockAccount(String account, HttpStatus missing) {
        var rows = db.queryForList("SELECT account_id FROM sc_directory.accounts WHERE account_id=? FOR UPDATE", String.class, account);
        if (rows.isEmpty()) throw new ResponseStatusException(missing);
    }

    void lockPeers(DeviceIdentity device, String peer) {
        if (device.accountId().compareTo(peer) < 0) {
            lockActive(device);
            lockAccount(peer, NOT_FOUND);
        } else {
            lockAccount(peer, NOT_FOUND);
            lockActive(device);
        }
    }

    public void requireContact(String account, String peer) {
        String first = account.compareTo(peer) < 0 ? account : peer;
        String second = first.equals(account) ? peer : account;
        var rows = db.queryForList("SELECT first_id FROM sc_directory.contacts WHERE first_id=? AND second_id=? FOR UPDATE",
            String.class, first, second);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
    }

    public boolean isBlocked(String recipient, String sender) {
        return !db.queryForList("SELECT peer_id FROM sc_directory.blocks WHERE account_id=? AND peer_id=? FOR UPDATE",
            String.class, recipient, sender).isEmpty();
    }

    @Transactional(noRollbackFor=ChallengeService.InvalidCode.class)
    public Access register(AccountRequest r) {
        Keys keys = Keys.from(r);
        invites.settings(true);
        String email = challenges.unverifiedRegistration ? ChallengeService.normalize(r.email()) :
            challenges.consume(r.challengeId(), r.code(), "register").email();
        var candidate = invites.lookup(r.inviteCode(), false);
        if (candidate.ownerId() != null) lockAccount(candidate.ownerId(), NOT_FOUND);
        var invite = invites.lookup(r.inviteCode(), true);
        String account = UUID.randomUUID().toString();
        try {
            db.update("INSERT INTO sc_directory.accounts(account_id,email) VALUES (?,?)", account, email);
        } catch (DuplicateKeyException exists) {
            throw new ResponseStatusException(CONFLICT, "Account already exists");
        }
        invites.claim(invite, account);
        return provision(account, keys);
    }

    @Transactional(noRollbackFor=ChallengeService.InvalidCode.class)
    public Access recover(AccountRequest r) {
        Keys keys = Keys.from(r);
        invites.settings(true);
        String account = challenges.consume(r.challengeId(), r.code(), "recover").accountId();
        lockAccount(account, NOT_FOUND);
        record PriorDevice(String id, byte[] identity) {}
        var devices = db.query("SELECT device_id,identity_key FROM sc_directory.devices WHERE account_id=? FOR UPDATE",
            (rs, n) -> new PriorDevice(rs.getString(1), rs.getBytes(2)), account);
        for (var previous : devices) {
            if (java.util.Arrays.equals(previous.identity(), keys.identity())) {
                throw new ResponseStatusException(CONFLICT, "Generate a new device identity");
            }
            retire(new DeviceIdentity(account, previous.id()), "DEVICE_RETIRED");
        }
        return provision(account, keys);
    }

    @Transactional
    public void close(DeviceIdentity device) {
        invites.settings(true);
        lockActive(device);
        retire(device, "ACCOUNT_CLOSED");
        db.update("""
            DELETE FROM sc_directory.email_challenges WHERE email=(SELECT email FROM sc_directory.accounts WHERE account_id=?)
            """, device.accountId());
        db.update("DELETE FROM sc_directory.accounts WHERE account_id=?", device.accountId());
        invites.reopenIfEmpty();
    }

    private void retire(DeviceIdentity device, String action) {
        db.update("INSERT INTO sc_ledger.revocations(account_id,device_id,action,occurred_ms) VALUES (?,?,?,?)",
            device.accountId(), device.deviceId(), action, clock.millis());
        events.publishEvent(new DeviceRetired(device.accountId(), device.deviceId()));
        db.update("DELETE FROM sc_directory.devices WHERE device_id=?", device.deviceId());
    }

    private Access provision(String account, Keys keys) {
        var access = new Access(account, UUID.randomUUID().toString(), DirectorySecrets.token());
        db.update("""
            INSERT INTO sc_directory.devices(device_id,account_id,token_hash,identity_key,signed_prekey,protocol_prekeys)
            VALUES (?,?,?,?,?,?)
            """, access.deviceId(), account, DirectorySecrets.digest(access.token()), keys.identity(), keys.signed(), keys.protocol());
        return access;
    }

    @Transactional
    public DirectoryInvites.Issued invite(DeviceIdentity device) {
        var settings = invites.settings(true);
        lockActive(device);
        return invites.create(device.accountId(), settings);
    }

    @Transactional
    public List<DirectoryInvites.Entry> invitations(DeviceIdentity device) {
        lockActive(device);
        return invites.list(device.accountId());
    }

    @Transactional
    public void revoke(DeviceIdentity device, String id) {
        lockActive(device);
        invites.revoke(device.accountId(), id);
    }

    @Transactional
    public void redeem(DeviceIdentity device, String code) {
        var candidate = invites.lookup(code, false);
        if (candidate.ownerId() == null) throw new ResponseStatusException(CONFLICT, "Bootstrap requires registration");
        lockPeers(device, candidate.ownerId());
        invites.claim(invites.lookup(code, true), device.accountId());
    }

    @Transactional
    public List<Contact> contacts(DeviceIdentity device) {
        lockActive(device);
        return db.query("""
            SELECT d.account_id,d.device_id,d.identity_key,b.peer_id
            FROM sc_directory.contacts c
            JOIN sc_directory.devices d ON d.account_id=CASE WHEN c.first_id=? THEN c.second_id ELSE c.first_id END
            LEFT JOIN sc_directory.blocks b ON b.account_id=? AND b.peer_id=d.account_id
            WHERE c.first_id=? OR c.second_id=? ORDER BY d.account_id
            """, (rs, n) -> new Contact(rs.getString(1), rs.getString(2), encode(rs.getBytes(3)), rs.getString(4)!=null),
            device.accountId(), device.accountId(), device.accountId(), device.accountId());
    }

    @Transactional
    public void block(DeviceIdentity device, String peer, boolean value) {
        lockPeers(device, peer);
        requireContact(device.accountId(), peer);
        if (value) {
            db.update("""
                INSERT INTO sc_directory.blocks(account_id,peer_id) VALUES (?,?)
                ON DUPLICATE KEY UPDATE account_id=VALUES(account_id)
                """, device.accountId(), peer);
        } else {
            db.update("DELETE FROM sc_directory.blocks WHERE account_id=? AND peer_id=?", device.accountId(), peer);
        }
    }

    static String encode(byte[] bytes) {
        return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
    }

    static byte[] decode(String input) {
        if (input == null || input.isEmpty() || input.length() > 21848) throw new ResponseStatusException(BAD_REQUEST, "Invalid public key");
        try {
            byte[] decoded = Base64.getDecoder().decode(input);
            if (decoded.length > 16384) throw new ResponseStatusException(BAD_REQUEST, "Public key exceeds 16 KiB");
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid Base64 public key");
        }
    }
}
