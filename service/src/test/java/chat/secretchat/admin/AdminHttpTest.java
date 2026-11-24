package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.assertThat;
import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"secretchat.admin.password=test-only", "secretchat.admin.username=operator",
        "spring.datasource.url=${SECRETCHAT_TEST_DB_URL:jdbc:h2:mem:secretchat_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
        "spring.datasource.username=${SECRETCHAT_TEST_DB_USERNAME:sa}",
        "spring.datasource.password=${SECRETCHAT_TEST_DB_PASSWORD:}"})
@ActiveProfiles("local")
@Import(AdminHttpTest.Time.class)
class AdminHttpTest {
    private static final long NOW = 100_000;
    @TestConfiguration
    static class Time {
        @Bean @Primary Clock testClock() {
            return Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate database;
    final HttpClient browser = HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();

    @BeforeEach void prepareIsolatedQueue() {
        assertThat(database.queryForObject("SELECT DATABASE()", String.class)).isEqualToIgnoringCase("secretchat_test");
        database.update("DELETE FROM queued_envelope WHERE device_id = 'http-test-device'");
        assertThat(database.queryForObject("SELECT COUNT(*) FROM queued_envelope", Long.class)).isZero();
    }

    @Test void anonymousVisitorGetsLoginPageButCannotReadMetrics() throws Exception {
        assertThat(get("/console").headers().firstValue("location")).contains("console/");
        var redirect = get("/console/");
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("location")).contains("login.html");
        assertThat(get("/console/login.html").statusCode()).isEqualTo(200);
        assertThat(get("/admin-api/queue").statusCode()).isEqualTo(401);
    }

    @Test void loginRejectsMissingTokenAndWrongPassword() throws Exception {
        assertThat(post("/admin-api/login", "username=operator&password=test-only", null).statusCode()).isEqualTo(403);
        assertThat(login("wrong").statusCode()).isEqualTo(401);
        assertThat(get("/admin-api/queue").statusCode()).isEqualTo(401);
    }

    @Test void authenticatedQueueCountsOnlyUnexpiredRowsAndReturnsAggregates() throws Exception {
        insert("first", NOW - 9100, NOW + 1);
        insert("next", NOW - 4000, NOW + 1000);
        insert("expired", NOW - 50000, NOW);
        assertThat(login("test-only").statusCode()).isEqualTo(204);
        var response = get("/admin-api/queue");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-store");
        assertThat(JsonPath.<Integer>read(response.body(), "$.pendingCount")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(response.body(), "$.oldestWaitSeconds")).isEqualTo(9);
        assertThat(JsonPath.<java.util.Map<String, Object>>read(response.body(), "$").keySet())
            .containsExactlyInAnyOrder("pendingCount", "oldestWaitSeconds");
        assertThat(get("/console/").body()).contains("队列概览");
    }

    @Test void emptyQueueAndLogoutSessionInvalidation() throws Exception {
        assertThat(login("test-only").statusCode()).isEqualTo(204);
        var response = get("/admin-api/queue");
        assertThat(JsonPath.<Integer>read(response.body(), "$.pendingCount")).isZero();
        assertThat((Object) JsonPath.read(response.body(), "$.oldestWaitSeconds")).isNull();
        assertThat(post("/admin-api/logout", "", null).statusCode()).isEqualTo(403);
        assertThat(get("/admin-api/queue").statusCode()).isEqualTo(200);
        assertThat(post("/admin-api/logout", "", token()).statusCode()).isEqualTo(204);
        assertThat(get("/admin-api/queue").statusCode()).isEqualTo(401);
        assertThat(get("/console/index.html").statusCode()).isEqualTo(302);
    }

    private void insert(String id, long enqueued, long expires) {
        database.update("INSERT INTO queued_envelope (id, device_id, enqueued_ms, expires_ms, ciphertext) VALUES (?, ?, ?, ?, ?)",
            id, "http-test-device", enqueued, expires, new byte[]{42});
    }

    private HttpResponse<String> login(String password) throws Exception {
        return post("/admin-api/login", "username=operator&password=" + password, token());
    }

    private String token() throws Exception {
        return JsonPath.read(get("/admin-api/session").body(), "$.token");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return browser.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        var builder = request(path).header("Content-Type", "application/x-www-form-urlencoded");
        if (token != null) builder.header("X-CSRF-TOKEN", token);
        return browser.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }
}
