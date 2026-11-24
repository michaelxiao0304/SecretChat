package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.Connection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

final class IsolatedDatabase {
    private IsolatedDatabase() {}

    static void check(JdbcTemplate db) {
        db.execute((ConnectionCallback<Void>) (Connection connection) -> {
            if ("MySQL".equals(connection.getMetaData().getDatabaseProductName())) {
                assertThat(connection.getCatalog()).as("Use a dedicated MySQL test instance")
                        .isEqualTo("secretchat_test");
            }
            return null;
        });
    }

    static void resetDirectory(JdbcTemplate db) {
        check(db);
        db.update("DELETE FROM sc_directory.accounts");
        db.update("DELETE FROM sc_directory.invites");
        db.update("UPDATE sc_directory.invite_settings SET invite_limit=5,bootstrap_used=FALSE");
        db.update("DELETE FROM sc_directory.email_challenges");
        db.update("DELETE FROM sc_directory.challenge_throttle");
        db.update("DELETE FROM sc_ledger.revocations");
    }
}
