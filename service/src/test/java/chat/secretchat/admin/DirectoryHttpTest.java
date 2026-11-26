package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"secretchat.admin.password=test-only",
    "spring.datasource.url=${SECRETCHAT_TEST_DB_URL:jdbc:h2:mem:directory_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
    "spring.datasource.username=${SECRETCHAT_TEST_DB_USERNAME:sa}", "spring.datasource.password=${SECRETCHAT_TEST_DB_PASSWORD:}"})
@ActiveProfiles("local")
@Import(DirectoryHttpTest.TestMail.class)
class DirectoryHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired TestDelivery mail;
    MockMvc http;

    @TestConfiguration
    static class TestMail {
        @Bean @Primary TestDelivery testDelivery(Environment env, ProviderFailures failures, ObjectMapper json, java.time.Clock clock) {
            return new TestDelivery(env, failures, json, clock);
        }
    }

    static class TestDelivery extends DeliveryProviders {
        final Map<String,String> codes = new ConcurrentHashMap<>();
        volatile boolean failDelivery;
        volatile boolean failRetirement;
        TestDelivery(Environment env, ProviderFailures failures, ObjectMapper json, java.time.Clock clock) {
            super(env, failures, json, clock);
        }
        @Override void sendEmail(String email, String code) {
            if (failDelivery) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            codes.put(email, code);
        }
        @EventListener void retiring(DeviceRetired event) {
            if (failRetirement) throw new IllegalStateException("simulated failure during device cleanup");
        }
    }

    @BeforeEach void setUp() {
        http = MockMvcBuilders.webAppContextSetup(context).build();
        IsolatedDatabase.resetDirectory(db);
        mail.codes.clear();
        mail.failDelivery = false;
        mail.failRetirement = false;
    }

    @Test void mobileContactDirectoryRequiresDeviceCredential() throws Exception {
        http.perform(get("/api/contacts")).andExpect(status().isUnauthorized());
        http.perform(get("/api/contacts").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
    }

    @Test void invitationsEstablishMutualContactsAndRevokedDevicesLoseAccess() throws Exception {
        String bootstrap = issueBootstrap();
        var alice = register("alice@example.test", bootstrap);
        request(post("/admin-api/invites").principal(() -> "admin"), null, null).andExpect(status().isConflict());
        var bob = register("bob@example.test", invitation(alice).get("code").asString());
        assertThat(body(request(get("/api/contacts"), alice, null)).get(0).get("accountId")).isEqualTo(bob.get("accountId"));
        assertThat(body(request(get("/api/contacts"), bob, null)).get(0).get("identityKey").asString()).isEqualTo("AQ==");

        String block = "/api/contacts/" + bob.get("accountId").asString() + "/block";
        request(put(block), alice, null).andExpect(status().isNoContent());
        assertThat(body(request(get("/api/contacts"), alice, null)).get(0).get("blocked").asBoolean()).isTrue();
        request(delete(block), alice, null).andExpect(status().isNoContent());
        assertThat(body(request(get("/api/contacts"), alice, null)).get(0).get("blocked").asBoolean()).isFalse();

        String recovery = ticket("bob@example.test", "recover");
        Map<String,String> proof = Map.of("challengeId", recovery, "code", mail.codes.get("bob@example.test"),
            "identityKey", "Aw==", "signedPrekey", "BA==");
        Map<String,String> oldIdentity = Map.of("challengeId",recovery, "code",mail.codes.get("bob@example.test"),
            "identityKey","AQ==","signedPrekey","BA==");
        request(post("/api/accounts/recover"),null,oldIdentity).andExpect(status().isConflict());
        var fresh = body(request(post("/api/accounts/recover"), null, proof).andExpect(status().isOk()));
        assertThat(fresh.get("accountId")).isEqualTo(bob.get("accountId"));
        assertThat(fresh.get("deviceId")).isNotEqualTo(bob.get("deviceId"));
        request(get("/api/contacts"), bob, null).andExpect(status().isUnauthorized());
        request(post("/api/accounts/recover"), null, proof).andExpect(status().isUnauthorized());
        assertThat(body(request(get("/api/contacts"), alice, null)).get(0).get("identityKey").asString()).isEqualTo("Aw==");
        assertThat(db.queryForList("SELECT action FROM sc_ledger.revocations", String.class)).containsExactly("DEVICE_RETIRED");

        request(delete("/api/accounts/me"), fresh, null).andExpect(status().isNoContent());
        assertThat(body(request(get("/api/contacts"), alice, null)).size()).isZero();
        assertThat(body(request(get("/api/invites"), alice, null)).size()).isZero();
        request(get("/api/contacts"), fresh, null).andExpect(status().isUnauthorized());
        request(delete("/api/accounts/me"), alice, null).andExpect(status().isNoContent());
        assertThat(issueBootstrap()).isNotEqualTo(bootstrap);
    }

    @Test void attemptLimitPersistsAndFreshChallengeReplacesEarlierCode() throws Exception {
        String invite = issueBootstrap();
        String email = "limited@example.test";
        String initial = ticket(email, "register");
        String latest = ticket(email, "register");
        var code = mail.codes.get(email);
        var wrong = code.equals("000000") ? "000001" : "000000";
        request(post("/api/accounts"), null, account(initial, code, invite)).andExpect(status().isUnauthorized());
        for (int n = 0; n < 5; n++) {
            request(post("/api/accounts"), null, account(latest, wrong, invite)).andExpect(status().isUnauthorized());
        }
        request(post("/api/accounts"), null, account(latest, code, invite)).andExpect(status().isUnauthorized());
        assertThat(register(email, invite).has("token")).isTrue();
        request(post("/api/challenges"), null, Map.of("email", email, "purpose", "recover")).andExpect(status().isTooManyRequests());
    }

    @Test void proxyAddressIsAcceptedOnlyFromTheLoopbackProxy() throws Exception {
        for (int n = 1; n <= 21; n++) {
            var email = Map.of("email","proxy"+n+"@example.test","purpose","register");
            var direct = post("/api/challenges").header("X-Real-IP", "192.0.2."+n)
                .with(req -> { req.setRemoteAddr("198.51.100.1"); return req; });
            request(direct, null, email).andExpect(n == 21 ? status().isTooManyRequests() : status().isOk());
            var proxy = post("/api/challenges").header("X-Real-IP","192.0.2."+n)
                .with(req -> { req.setRemoteAddr("127.0.0.1"); return req; });
            request(proxy,null,email).andExpect(status().isOk());
        }
    }

    @Test void providerFailureInvalidatesChallengeAndExpiredProofCannotRegister() throws Exception {
        mail.failDelivery = true;
        var input = Map.of("email","failed@example.test","purpose","register");
        request(post("/api/challenges"), null, input).andExpect(status().isServiceUnavailable());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sc_directory.email_challenges", Integer.class)).isZero();
        mail.failDelivery = false;
        String id = ticket("expired@example.test", "register");
        db.update("UPDATE sc_directory.email_challenges SET expires_ms=0 WHERE challenge_id=?", id);
        request(post("/api/accounts"), null, account(id, mail.codes.get("expired@example.test"), issueBootstrap()))
            .andExpect(status().isUnauthorized());
    }

    @Test void conflictingInviteAttemptsAndPrekeyClaimsAreSerialized() throws Exception {
        var alice = register("alice@example.test", issueBootstrap());
        String invite = invitation(alice).get("code").asString();
        String first = ticket("first@example.test", "register");
        String second = ticket("second@example.test", "register");
        var attempts = concurrently(
            () -> request(post("/api/accounts"), null, account(first, mail.codes.get("first@example.test"), invite)).andReturn(),
            () -> request(post("/api/accounts"), null, account(second, mail.codes.get("second@example.test"), invite)).andReturn());
        assertThat(attempts.stream().map(r -> r.getResponse().getStatus())).containsExactlyInAnyOrder(201,409);
        var bob = json.readTree(attempts.stream().filter(r -> r.getResponse().getStatus()==201).findFirst().orElseThrow().getResponse().getContentAsString());
        var keys = Map.of("keys", List.of(Map.of("id", 7, "material", "BQ==")));
        assertThat(body(request(post("/api/keys/one-time"), bob, keys)).get("remaining").asInt()).isEqualTo(1);
        String path = "/api/contacts/" + bob.get("accountId").asString() + "/prekeys";
        var claimed = concurrently(() -> body(request(post(path), alice, null)), () -> body(request(post(path), alice, null)));
        assertThat(claimed.stream().filter(n -> !n.get("oneTimePrekey").isNull()).count()).isOne();
        assertThat(claimed.stream().map(n -> n.get("signedPrekey").asString())).containsOnly("Ag==");
        assertThat(body(request(post("/api/keys/one-time"), bob, keys)).get("remaining").asInt()).isZero();
        request(put("/api/keys/signed"), bob, Map.of("signedPrekey","Bw==")).andExpect(status().isNoContent());
        assertThat(body(request(post(path), alice, null)).get("signedPrekey").asString()).isEqualTo("Bw==");
    }

    @Test void keyMaterialLimitsAreValidatedBeforeDeviceAndKeyUpdates() throws Exception {
        String unusable = "A".repeat(21849);
        var alice = register("keys@example.test", issueBootstrap());
        for (String bad : List.of("", "?", unusable, "A".repeat(21848))) {
            request(put("/api/keys/signed"), alice, Map.of("signedPrekey",bad)).andExpect(status().isBadRequest());
        }
        var endpoint = "/api/keys/one-time";
        request(post(endpoint), alice, Map.of("keys",List.of())).andExpect(status().isBadRequest());
        request(post(endpoint), alice, Map.of("keys",List.of(Map.of("id",1,"material","AQ=="),
            Map.of("id",-1,"material","AQ==")))).andExpect(status().isBadRequest());
        assertThat(body(request(get(endpoint),alice,null)).get("remaining").asInt()).isZero();
        request(post(endpoint),alice,Map.of("keys",List.of(Map.of("id",1,"material","AQ==")))).andExpect(status().isOk());
        request(post(endpoint),alice,Map.of("keys",List.of(Map.of("id",1,"material","Ag==")))).andExpect(status().isConflict());
    }

    @Test void redemptionIsIdempotentAndOutstandingCapAndRevocationApplyToOwner() throws Exception {
        var alice = register("alice@example.test", issueBootstrap());
        var bob = register("bob@example.test", invitation(alice).get("code").asString());
        var carol = register("carol@example.test", invitation(alice).get("code").asString());
        request(put("/admin-api/invites/settings").principal(() -> "operator"), null, Map.of("limit",1)).andExpect(status().isOk());
        var invite = invitation(bob);
        request(post("/api/invites"), bob, null).andExpect(status().isConflict());
        request(delete("/api/invites/"+invite.get("id").asString()), alice, null).andExpect(status().isNoContent());
        request(delete("/api/invites/"+invite.get("id").asString()), bob, null).andExpect(status().isNoContent());
        request(post("/api/invites/redeem"), carol, Map.of("code",invite.get("code").asString())).andExpect(status().isNotFound());
        String usable = invitation(bob).get("code").asString();
        for (int n=0; n<2; n++) request(post("/api/invites/redeem"), carol, Map.of("code",usable)).andExpect(status().isNoContent());
        request(post("/api/invites/redeem"), alice, Map.of("code",usable)).andExpect(status().isConflict());
        assertThat(body(request(get("/api/contacts"), carol, null)).size()).isEqualTo(2);
    }

    @Test void retirementListenerFailureRollsBackCredentialJournalAndChallenge() throws Exception {
        var alice = register("tx@example.test", issueBootstrap());
        String recovery = ticket("tx@example.test","recover");
        Map<String,String> input = Map.of("challengeId",recovery,"code",mail.codes.get("tx@example.test"),"identityKey","Aw==","signedPrekey","BA==");
        mail.failRetirement = true;
        assertThatThrownBy(() -> request(post("/api/accounts/recover"), null, input)).hasRootCauseInstanceOf(IllegalStateException.class);
        request(get("/api/contacts"), alice, null).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sc_ledger.revocations", Integer.class)).isZero();
        mail.failRetirement = false;
        request(post("/api/accounts/recover"), null, input).andExpect(status().isOk());
    }

    private <T> List<T> concurrently(Callable<T> a, Callable<T> b) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        try {
            var results = List.of(a,b).stream().map(call -> executor.submit(() -> {
                ready.countDown();
                if (!ready.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Workers did not start");
                return call.call();
            })).toList();
            return List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private String issueBootstrap() throws Exception {
        return body(request(post("/admin-api/invites").principal(() -> "admin"),null,null).andExpect(status().isOk())).get("code").asString();
    }

    private String ticket(String email, String purpose) throws Exception {
        return body(request(post("/api/challenges"),null,Map.of("email",email,"purpose",purpose))
            .andExpect(status().isOk())).get("challengeId").asString();
    }

    private Map<String,String> account(String challenge, String code, String invite) {
        return Map.of("challengeId",challenge, "code",code, "inviteCode",invite, "identityKey","AQ==", "signedPrekey","Ag==");
    }

    private JsonNode register(String email, String invite) throws Exception {
        var challenge = ticket(email,"register");
        return body(request(post("/api/accounts"),null,account(challenge,mail.codes.get(email),invite)).andExpect(status().isCreated()));
    }

    private JsonNode invitation(JsonNode account) throws Exception {
        return body(request(post("/api/invites"), account,null).andExpect(status().isOk()));
    }

    private ResultActions request(MockHttpServletRequestBuilder request, JsonNode account, Object data) throws Exception {
        if (account != null) request.header("Authorization","Bearer "+account.get("token").asString());
        if (data != null) request.contentType("application/json").content(json.writeValueAsBytes(data));
        return http.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
