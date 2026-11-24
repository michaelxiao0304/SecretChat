package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"secretchat.admin.password=test-only",
    "spring.datasource.url=${SECRETCHAT_TEST_DB_URL:jdbc:h2:mem:report_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
    "spring.datasource.username=${SECRETCHAT_TEST_DB_USERNAME:sa}", "spring.datasource.password=${SECRETCHAT_TEST_DB_PASSWORD:}"})
@ActiveProfiles("local")
class ReportHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate db;
    @Autowired OpsAudit audit;
    @Autowired Clock clock;
    @Autowired ObjectMapper json;
    @Autowired ReportKey key;
    MockMvc http;
    String token = DirectorySecrets.token();
    String reporter = UUID.randomUUID().toString();
    Map<String,Object> fields = new HashMap<>();

    @DynamicPropertySource
    static void recipient(DynamicPropertyRegistry properties) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var spki = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        properties.add("secretchat.report.public-key", () -> spki);
    }

    @BeforeEach void prepare() {
        IsolatedDatabase.check(db);
        http = MockMvcBuilders.webAppContextSetup(context).build();
        db.update("DELETE FROM sc_ops.report_ticket");
        db.update("DELETE FROM sc_ops.audit_event");
        db.update("INSERT INTO sc_directory.accounts(account_id,email) VALUES(?,?)",reporter,reporter + "@example.test");
        db.update("INSERT INTO sc_directory.devices(device_id,account_id,token_hash,identity_key,signed_prekey) VALUES(?,?,?,?,?)",
            UUID.randomUUID().toString(),reporter,DirectorySecrets.digest(token),new byte[]{1},new byte[]{2});
        String reported = UUID.randomUUID().toString();
        db.update("INSERT INTO sc_directory.accounts(account_id,email) VALUES(?,?)",reported,reported + "@example.test");
        var pair = java.util.stream.Stream.of(reporter,reported).sorted().toList();
        db.update("INSERT INTO sc_directory.contacts(first_id,second_id) VALUES(?,?)",pair.get(0),pair.get(1));
        fields.putAll(Map.of("id",UUID.randomUUID().toString(),"reportedAccountId",reported,"version",1,
            "keyId",key.recipient().keyId(),"wrappedKey",encoded(256),"iv",encoded(12),"ciphertext",encoded(17)));
    }

    @Test void anonymousDeviceCannotFetchTheReportRecipient() throws Exception {
        http.perform(get("/api/reports/key")).andExpect(status().isUnauthorized());
        http.perform(get("/api/reports/key").header("Authorization","Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.publicKey").value(key.recipient().publicKey()));
    }

    @Test void envelopesAcceptOnlyTheBoundedVersionAndBinaryFields() throws Exception {
        expectInvalid("plaintext","message text");
        expectInvalid("id","1-1-1-1-1");
        expectInvalid("reportedAccountId",reporter);
        expectInvalid("reportedAccountId",null);
        expectInvalid("version",2);
        expectInvalid("wrappedKey",encoded(255));
        expectInvalid("iv",encoded(13));
        expectInvalid("ciphertext","!");
        expectInvalid("ciphertext",encoded(16));
        expectInvalid("ciphertext",encoded(ReportSubmission.MAX_CIPHERTEXT + 1));
        http.perform(post("/api/reports").header("Authorization","Bearer " + token)
            .contentType("application/json").content(" ".repeat(73729))).andExpect(status().is(413));
        fields.put("keyId","wrong-key");
        send(fields).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sc_ops.report_ticket",Integer.class)).isZero();
    }

    @Test void onlyAnActiveDeviceCanReportItsOwnContactsIncludingBlockedContacts() throws Exception {
        String other = UUID.randomUUID().toString();
        db.update("INSERT INTO sc_directory.accounts(account_id,email) VALUES(?,?)",other,other + "@example.test");
        var unrelated = new HashMap<>(fields);
        unrelated.put("reportedAccountId",other);
        send(unrelated).andExpect(status().isNotFound());
        db.update("INSERT INTO sc_directory.blocks(account_id,peer_id) VALUES(?,?)",reporter,fields.get("reportedAccountId"));
        send(fields).andExpect(status().isOk());
        db.update("DELETE FROM sc_directory.devices WHERE account_id=?",reporter);
        send(fields).andExpect(status().isUnauthorized());
    }

    @Test void reportIsIdempotentListedWithoutContentAndAuditedOnReadAndClose() throws Exception {
        var id = fields.get("id");
        fields.put("ciphertext",encoded(ReportSubmission.MAX_CIPHERTEXT));
        send(fields).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        send(fields).andExpect(status().isOk());
        Map<String,Object> conflict = new HashMap<>(fields);
        conflict.put("ciphertext",encoded(18));
        send(conflict).andExpect(status().isConflict());
        http.perform(get("/admin-api/reports").principal(()->"reviewer")).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].ciphertext").doesNotExist()).andExpect(jsonPath("$[0].reporterAccountId").value(reporter));
        http.perform(get("/admin-api/reports/" + id).principal(()->"reviewer"))
            .andExpect(jsonPath("$.ciphertext").value(fields.get("ciphertext")));
        for (int i=0;i<2;i++) http.perform(post("/admin-api/reports/" + id + "/close").principal(()->"reviewer"))
            .andExpect(status().isNoContent());
        assertThat(db.queryForList("SELECT action_name FROM sc_ops.audit_event ORDER BY seq",String.class))
            .containsExactly("REPORT_LIST","REPORT_READ","REPORT_CLOSE");
        assertThat(db.queryForObject("SELECT subject_id FROM sc_ops.audit_event WHERE action_name='REPORT_READ'",String.class))
            .isEqualTo(id);
    }

    @Test void expiredClosedMaterialAndAuditDisappearWhileOpenReportsSurvive() throws Exception {
        send(fields).andExpect(status().isOk());
        var expired = fields.get("id");
        fields.put("id",UUID.randomUUID().toString());
        send(fields).andExpect(status().isOk());
        var keep = fields.get("id");
        long expiredTime = clock.millis() - OpsAudit.RETENTION_MS;
        db.update("UPDATE sc_ops.report_ticket SET closed_ms=? WHERE id=?",expiredTime,expired);
        audit.record("reviewer","REPORT_CLOSE",expired.toString());
        db.update("UPDATE sc_ops.audit_event SET occurred_ms=?",expiredTime);
        http.perform(get("/admin-api/reports/" + expired).principal(()->"reviewer")).andExpect(status().isNotFound());
        http.perform(get("/admin-api/reports").principal(()->"reviewer")).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(keep));
        var before = db.queryForObject("SELECT seq FROM sc_ops.report_ticket WHERE id=?",Long.class,keep);
        http.perform(get("/admin-api/reports").param("before",before.toString()).principal(()->"reviewer"))
            .andExpect(jsonPath("$.length()").value(0));
        http.perform(get("/admin-api/audit").principal(()->"admin")).andExpect(jsonPath("$[0].action").value("AUDIT_LIST"));
        audit.purgeExpired();
        assertThat(db.queryForList("SELECT id FROM sc_ops.report_ticket",String.class)).containsExactly(keep.toString());
        assertThat(db.queryForList("SELECT action_name FROM sc_ops.audit_event",String.class)).doesNotContain("REPORT_CLOSE");
    }

    private void expectInvalid(String name,Object value) throws Exception {
        Map<String,Object> invalid = new HashMap<>(fields);
        invalid.put(name,value);
        send(invalid).andExpect(status().isBadRequest());
    }

    private ResultActions send(Map<String,Object> body) throws Exception {
        return http.perform(post("/api/reports").header("Authorization","Bearer " + token).contentType("application/json")
            .content(json.writeValueAsBytes(body)));
    }

    private static String encoded(int size) {
        return Base64.getEncoder().encodeToString(new byte[size]);
    }
}
