package chat.secretchat.admin;

import static org.springframework.http.HttpStatus.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class DirectoryPrekeys {
    record OneTime(Long id, String material) {}
    record Batch(List<OneTime> keys) {}
    record Count(int remaining) {}
    record Signed(String signedPrekey, String protocolPrekeys) {}
    record Bundle(String accountId, String deviceId, String identityKey, String signedPrekey,
                  String protocolPrekeys, OneTime oneTimePrekey) {}
    private final JdbcTemplate db;
    private final DirectoryService directory;

    DirectoryPrekeys(JdbcTemplate db, DirectoryService directory) {
        this.db = db;
        this.directory = directory;
    }

    @Transactional
    public Count count(DeviceIdentity device) {
        directory.lockActive(device);
        return remaining(device.deviceId());
    }

    @Transactional
    public Count upload(DeviceIdentity device, Batch batch) {
        if (batch.keys() == null || batch.keys().isEmpty() || batch.keys().size() > 100) {
            throw new ResponseStatusException(BAD_REQUEST, "Supply 1 to 100 one-time public prekeys");
        }
        directory.lockActive(device);
        for (var key : batch.keys()) add(device.deviceId(), key);
        return remaining(device.deviceId());
    }

    private Count remaining(String device) {
        int count = db.queryForObject("""
            SELECT COUNT(*) FROM sc_directory.one_time_prekeys WHERE device_id=? AND material IS NOT NULL
            """, Integer.class, device);
        return new Count(count);
    }

    private void add(String device, OneTime key) {
        if (key == null || key.id() == null || key.id() < 0 || key.id() > 0xffff_ffffL) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid prekey ID");
        }
        byte[] material = DirectoryService.decode(key.material());
        var rows = db.query("SELECT material FROM sc_directory.one_time_prekeys WHERE device_id=? AND key_id=? FOR UPDATE",
            (rs, n) -> rs.getBytes(1), device, key.id());
        if (rows.isEmpty()) {
            db.update("INSERT INTO sc_directory.one_time_prekeys(device_id,key_id,material) VALUES (?,?,?)",
                device, key.id(), material);
        } else if (rows.get(0) != null && !java.util.Arrays.equals(rows.get(0), material)) {
            throw new ResponseStatusException(CONFLICT, "Prekey ID already in use");
        }
    }

    @Transactional
    public void updateSigned(DeviceIdentity device, Signed signed) {
        directory.lockActive(device);
        db.update("UPDATE sc_directory.devices SET signed_prekey=?,protocol_prekeys=? WHERE device_id=?",
            DirectoryService.decode(signed.signedPrekey()),
            signed.protocolPrekeys() == null ? null : DirectoryService.decode(signed.protocolPrekeys()), device.deviceId());
    }

    @Transactional
    public Bundle claim(DeviceIdentity requester, String peer) {
        directory.lockPeers(requester, peer);
        directory.requireContact(requester.accountId(), peer);
        var rows = db.query("""
            SELECT device_id,identity_key,signed_prekey,protocol_prekeys FROM sc_directory.devices
            WHERE account_id=? FOR UPDATE
            """, (rs, n) -> new Bundle(peer, rs.getString(1), DirectoryService.encode(rs.getBytes(2)),
                DirectoryService.encode(rs.getBytes(3)), DirectoryService.encode(rs.getBytes(4)), null), peer);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
        var bundle = rows.get(0);
        var keys = db.query("""
            SELECT key_id,material FROM sc_directory.one_time_prekeys
            WHERE device_id=? AND material IS NOT NULL ORDER BY key_id LIMIT 1 FOR UPDATE
            """, (rs, n) -> new OneTime(rs.getLong(1), DirectoryService.encode(rs.getBytes(2))), bundle.deviceId());
        if (keys.isEmpty()) return bundle;
        var key = keys.get(0);
        db.update("UPDATE sc_directory.one_time_prekeys SET material=NULL WHERE device_id=? AND key_id=?",
            bundle.deviceId(), key.id());
        return new Bundle(peer, bundle.deviceId(), bundle.identityKey(), bundle.signedPrekey(), bundle.protocolPrekeys(), key);
    }
}
