package chat.secretchat.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ErrorHandler;

@ExtendWith(OutputCaptureExtension.class)
class DiagnosticConfigurationTest {
    @Test void unexpectedFailureUsesSafeCodeLocationAndLeavesNoThreadContext(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/raw-path-marker");
        var response = new MockHttpServletResponse();
        var diagnostics = new RequestDiagnostics();
        diagnostics.doFilter(request, response, (req, res) -> {
            throw new ServletException("raw-wrapper-marker", new IllegalStateException("raw-cause-marker"));
        });
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(output.getAll()).contains("route=/api/** status=500", "source=DiagnosticConfigurationTest.")
            .doesNotContain("raw-path-marker", "raw-wrapper-marker", "raw-cause-marker");
    }

    @Test void schedulerReportsCodeLocationWithoutDatabaseValues(CapturedOutput output) {
        var scheduler = mock(ThreadPoolTaskScheduler.class);
        new DiagnosticConfiguration().scheduledFailures().customize(scheduler);
        var handler = ArgumentCaptor.forClass(ErrorHandler.class);
        verify(scheduler).setErrorHandler(handler.capture());
        handler.getValue().handleError(new IllegalStateException("sensitive-sql-value"));
        assertThat(output.getAll()).contains("event=scheduled_failure error=IllegalStateException",
            "source=DiagnosticConfigurationTest.schedulerReportsCodeLocationWithoutDatabaseValues")
            .doesNotContain("sensitive-sql-value");
    }

    @Test void disconnectAfterCommitPreservesWireStatusAndRedactsTheTransportError(CapturedOutput output) {
        var request = new MockHttpServletRequest("arbitrary-input-marker", "/api/unknown");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> new RequestDiagnostics().doFilter(request, response, (req, res) -> {
            res.flushBuffer();
            throw new java.io.IOException("raw-network-marker");
        })).isInstanceOf(java.io.IOException.class).hasMessage("HTTP transport interrupted");
        assertThat(output.getAll()).contains("method=OTHER route=/api/** status=200", "error=IOException")
            .doesNotContain("arbitrary-input-marker", "raw-network-marker");
        assertThat(MDC.get("requestId")).isNull();
    }
}
