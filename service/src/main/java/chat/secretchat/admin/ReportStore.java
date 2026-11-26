package chat.secretchat.admin;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class ReportStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OpsAudit audit;
    private final DirectoryService directory;
    ReportStore(JdbcTemplate jdbc, Clock clock, OpsAudit audit, DirectoryService directory) {
        this.directory = directory;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
    }

    record Summary(long seq, String id, String reporterAccountId, String reportedAccountId, long createdMs, Long closedMs) {}
    record Detail(String id, String reporterAccountId, String reportedAccountId, Long closedMs, int version,
                  String keyId, byte[] wrappedKey, byte[] iv, byte[] ciphertext) {
        boolean matches(String reporter, ReportSubmission submission) {
            return reporterAccountId.equals(reporter) && reportedAccountId.equals(submission.reportedAccountId())
                && keyId.equals(submission.keyId()) && Arrays.equals(wrappedKey, submission.wrappedKey())
                && Arrays.equals(iv, submission.iv()) && Arrays.equals(ciphertext, submission.ciphertext());
        }
    }

    @Transactional
    public void submit(DeviceIdentity device, ReportSubmission submission) {
        String reporter = device.accountId();
        if (reporter.equals(submission.reportedAccountId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        directory.lockPeers(device, submission.reportedAccountId());
        directory.requireContact(reporter, submission.reportedAccountId());
        jdbc.update("""
            INSERT INTO sc_ops.report_ticket(id,reporter_id,reported_id,created_ms,key_id,wrapped_key,iv,ciphertext)
            VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
            """, submission.id(), reporter, submission.reportedAccountId(), clock.millis(), submission.keyId(),
            submission.wrappedKey(), submission.iv(), submission.ciphertext());
        if (!detail(submission.id()).matches(reporter, submission)) throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    @Transactional
    public List<Summary> list(String operator, long before) {
        audit.record(operator, "REPORT_LIST", "-");
        return jdbc.query("""
            SELECT seq,id,reporter_id,reported_id,created_ms,closed_ms FROM sc_ops.report_ticket
            WHERE seq < ? AND (closed_ms IS NULL OR closed_ms > ?) ORDER BY seq DESC LIMIT 50
            """, (rs,n) -> new Summary(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getLong(5),rs.getObject(6,Long.class)), before, cutoff());
    }

    @Transactional
    public Detail read(String operator, String id) {
        Detail row = detail(id);
        audit.record(operator, "REPORT_READ", id);
        return row;
    }

    private Detail detail(String id) {
        return jdbc.query("""
            SELECT id,reporter_id,reported_id,closed_ms,key_id,wrapped_key,iv,ciphertext FROM sc_ops.report_ticket
            WHERE id = ? AND (closed_ms IS NULL OR closed_ms > ?)
            """, (rs,n) -> new Detail(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4,Long.class),
                1,rs.getString(5),rs.getBytes(6),rs.getBytes(7),rs.getBytes(8)), id, cutoff())
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public void close(String operator, String id) {
        int count = jdbc.update("UPDATE sc_ops.report_ticket SET closed_ms = ? WHERE id = ? AND closed_ms IS NULL", clock.millis(), id);
        if (count == 1) audit.record(operator, "REPORT_CLOSE", id);
        else detail(id);
    }

    private long cutoff() {
        return clock.millis() - OpsAudit.RETENTION_MS;
    }
}
