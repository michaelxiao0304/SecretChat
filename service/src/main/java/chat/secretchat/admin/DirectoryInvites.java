package chat.secretchat.admin;

import static org.springframework.http.HttpStatus.*;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class DirectoryInvites {
    record Settings(int limit, boolean bootstrapAvailable) {}
    record Issued(String id, String code) {}
    record Entry(String id, String redeemedBy) {}
    record Claim(String id, String ownerId, String redeemedBy) {}

    private final JdbcTemplate db;
    private final OpsAudit audit;
    DirectoryInvites(JdbcTemplate db, OpsAudit audit) {
        this.db = db;
        this.audit = audit;
    }

    Settings settings(boolean lock) {
        return db.queryForObject("SELECT invite_limit,bootstrap_used FROM sc_directory.invite_settings WHERE settings_id=1"
                + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new Settings(rs.getInt(1), !rs.getBoolean(2)));
    }

    @Transactional
    public Settings adminSettings(String operator) {
        audit.record(operator, "invite.settings", "-");
        return settings(false);
    }

    @Transactional
    public Settings changeLimit(String operator, Integer limit) {
        if (limit == null || limit < 0 || limit > 100) throw new ResponseStatusException(BAD_REQUEST);
        db.update("UPDATE sc_directory.invite_settings SET invite_limit=? WHERE settings_id=1", limit);
        audit.record(operator, "invite.limit", Integer.toString(limit));
        return settings(false);
    }

    @Transactional
    public Issued bootstrap(String operator) {
        if (!settings(true).bootstrapAvailable()) throw new ResponseStatusException(CONFLICT, "Bootstrap already used");
        db.update("DELETE FROM sc_directory.invites WHERE owner_id IS NULL AND redeemed_by IS NULL");
        Issued invite = insert(null);
        audit.record(operator, "invite.bootstrap", invite.id());
        return invite;
    }

    void reopenIfEmpty() {
        if (db.queryForList("SELECT account_id FROM sc_directory.accounts LIMIT 1", String.class).isEmpty()) {
            db.update("DELETE FROM sc_directory.invites WHERE owner_id IS NULL");
            db.update("UPDATE sc_directory.invite_settings SET bootstrap_used=FALSE WHERE settings_id=1");
        }
    }

    Issued create(String owner, Settings settings) {
        int outstanding = db.queryForObject("""
            SELECT COUNT(*) FROM sc_directory.invites WHERE owner_id=? AND redeemed_by IS NULL
            """, Integer.class, owner);
        if (outstanding >= settings.limit()) throw new ResponseStatusException(CONFLICT, "Invitation limit reached");
        return insert(owner);
    }

    List<Entry> list(String owner) {
        return db.query("SELECT invite_id,redeemed_by FROM sc_directory.invites WHERE owner_id=? ORDER BY invite_id",
            (rs, n) -> new Entry(rs.getString(1), rs.getString(2)), owner);
    }

    void revoke(String owner, String id) {
        db.update("DELETE FROM sc_directory.invites WHERE owner_id=? AND invite_id=? AND redeemed_by IS NULL", owner, id);
    }

    Claim lookup(String code, boolean lock) {
        if (!DirectorySecrets.validToken(code)) throw new ResponseStatusException(NOT_FOUND, "Invalid invitation");
        var rows = db.query("SELECT invite_id,owner_id,redeemed_by FROM sc_directory.invites WHERE code_hash=?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new Claim(rs.getString(1), rs.getString(2), rs.getString(3)), DirectorySecrets.digest(code));
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Invalid invitation");
        return rows.get(0);
    }

    void claim(Claim invite, String account) {
        if (invite.redeemedBy() != null) {
            if (account.equals(invite.redeemedBy())) return; // The same signed-in redeemer can retry safely.
            throw new ResponseStatusException(CONFLICT, "Invitation already used");
        }
        if (account.equals(invite.ownerId())) throw new ResponseStatusException(CONFLICT, "Cannot invite yourself");
        if (invite.ownerId() == null) {
            int claimed = db.update("""
                UPDATE sc_directory.invite_settings SET bootstrap_used=TRUE WHERE settings_id=1 AND bootstrap_used=FALSE
                """);
            if (claimed == 0) throw new ResponseStatusException(CONFLICT, "Bootstrap already used");
        } else {
            String first = account.compareTo(invite.ownerId()) < 0 ? account : invite.ownerId();
            String second = first.equals(account) ? invite.ownerId() : account;
            db.update("""
                INSERT INTO sc_directory.contacts(first_id,second_id) VALUES (?,?)
                ON DUPLICATE KEY UPDATE first_id=VALUES(first_id)
                """, first, second);
        }
        db.update("UPDATE sc_directory.invites SET redeemed_by=? WHERE invite_id=?", account, invite.id());
    }

    private Issued insert(String owner) {
        var invite = new Issued(UUID.randomUUID().toString(), DirectorySecrets.token());
        db.update("INSERT INTO sc_directory.invites(invite_id,code_hash,owner_id) VALUES (?,?,?)",
            invite.id(), DirectorySecrets.digest(invite.code()), owner);
        return invite;
    }
}
