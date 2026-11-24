package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"secretchat.admin.password=log-test","spring.datasource.url=jdbc:h2:mem:log_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE"})
@ExtendWith(OutputCaptureExtension.class)
class RequestDiagnosticsHttpTest {
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();

    @Test void mapsDeviceFailureToSourceAndHidesRequestData(CapturedOutput output) throws Exception {
        var request = at("/api/contacts/c95d3845-bf9a-4457-aa02-945effe6edd8/block?token=query-marker")
            .header("Authorization", "Bearer private-token-marker")
            .header("X-Request-ID", "client-injected-marker")
            .PUT(HttpRequest.BodyPublishers.ofString("body-secret-marker")).build();
        var reply = http.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(reply.statusCode()).isEqualTo(401);
        String line = completion(reply, output);
        assertThat(line).contains("method=PUT", "route=/api/contacts/{peer}/block", "status=401",
                "source=DirectoryService.requireDevice").containsPattern("durationMs=\\d+");
        assertThat(output.getAll()).doesNotContain("c95d3845-bf9a-4457-aa02-945effe6edd8", "query-marker",
            "private-token-marker", "client-injected-marker", "body-secret-marker");
    }

    @Test void securityRejectionsAndSuccessfulRequestsGetSeparateIds(CapturedOutput output) throws Exception {
        var rejected = http.send(at("/admin-api/login?test=query-redaction-marker")
            .POST(HttpRequest.BodyPublishers.ofString("password=private-password-marker")).build(),
            HttpResponse.BodyHandlers.discarding());
        var ok = http.send(at("/admin-api/session").GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(completion(rejected, output)).contains("route=/admin-api/login", "status=403");
        assertThat(completion(ok, output)).contains("route=/admin-api/session", "status=200");
        assertThat(id(ok)).isNotEqualTo(id(rejected));
        assertThat(output.getAll()).doesNotContain("query-redaction-marker", "private-password-marker");
    }

    private HttpRequest.Builder at(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private String id(HttpResponse<?> response) {
        String value = response.headers().firstValue("X-Request-ID").orElseThrow();
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
        return value;
    }

    private String completion(HttpResponse<?> response, CapturedOutput output) throws InterruptedException {
        String requestId = "requestId=" + id(response);
        // A committed response can reach the client just before its filter's finally block runs.
        for (int attempt=0; attempt<100; attempt++) {
            var line = output.getAll().lines()
                .filter(s -> s.contains(requestId) && s.contains("event=http_request")).findFirst();
            if (line.isPresent()) return line.get();
            Thread.sleep(10);
        }
        throw new AssertionError("No request completion for " + requestId);
    }
}
