package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"secretchat.admin.password=test-only","secretchat.registration.unverified=true",
    "spring.datasource.url=${SECRETCHAT_TEST_DB_URL:jdbc:h2:mem:directory_unverified;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
    "spring.datasource.username=${SECRETCHAT_TEST_DB_USERNAME:sa}", "spring.datasource.password=${SECRETCHAT_TEST_DB_PASSWORD:}"})
@ActiveProfiles("local")
@Import(DirectoryHttpTest.TestMail.class)
class DirectoryUnverifiedTest {
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired DirectoryService directory;
    @Autowired DirectoryInvites invites;
    @Autowired ChallengeService challenges;
    @Autowired DirectoryHttpTest.TestDelivery mail;

    @Test void acceptsUnverifiedIdentifierOnlyForNewAccountsAndPreservesInvitesOnConflict() {
        IsolatedDatabase.resetDirectory(db);
        var seed = invites.bootstrap("operator");
        var created = directory.register(input("  This Is A Test  ", seed.code(), null, null));
        var first = directory.requireDevice("Bearer " + created.token());
        assertThat(mail.codes).isEmpty();
        var valid = directory.invite(first);
        assertThatThrownBy(() -> directory.register(input("THIS IS A TEST",valid.code(),null,null)))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(() -> directory.recover(input("this is a test",null,null,null)))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        assertThatThrownBy(() -> challenges.issue("tester@test.example","register","127.0.0.1"))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        assertThat(mail.codes).isEmpty();
        String email = UUID.randomUUID()+"@example.test";
        var previous = challenges.issue(email,"recover","127.0.0.1");
        String code = mail.codes.get(email);
        var next = directory.register(input(email,valid.code(),null,null));
        assertThatThrownBy(() -> directory.recover(input(email,null,previous.challengeId(),code)))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        assertThat(directory.requireDevice("Bearer "+next.token()).accountId()).isEqualTo(next.accountId());
    }

    private DirectoryService.AccountRequest input(String email, String invite, String challenge, String code) {
        return new DirectoryService.AccountRequest(email, challenge, code, invite, "AQ==", "Ag==", null);
    }
}
