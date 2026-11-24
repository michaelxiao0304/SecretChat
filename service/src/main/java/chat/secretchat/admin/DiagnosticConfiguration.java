package chat.secretchat.admin;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class DiagnosticConfiguration implements WebMvcConfigurer {
    static final String FAILURE = DiagnosticConfiguration.class.getName() + ".failure";
    private static final Logger LOG = LoggerFactory.getLogger(DiagnosticConfiguration.class);

    @Override public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(0, (request, response, handler, exception) -> {
            request.setAttribute(FAILURE, exception);
            return null; // Let the existing MVC resolvers choose the response.
        });
    }

    @Bean ThreadPoolTaskSchedulerCustomizer scheduledFailures() {
        return scheduler -> scheduler.setErrorHandler(failure ->
            LOG.error("event=scheduled_failure error={} source={}",
                failure.getClass().getSimpleName(), FailureSite.of(failure)));
    }
}
