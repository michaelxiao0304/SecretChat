package chat.secretchat.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class RequestDiagnostics extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestDiagnostics.class);
    private static final Set<String> METHODS = Set.of("GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS");
    private static final Set<String> SECURITY_ROUTES = Set.of("/admin-api/login","/admin-api/logout","/ws");
    private static final Set<String> PAGES = Set.of("/console","/console/","/console/index.html","/console/login.html");

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !(path.startsWith("/api/") || path.startsWith("/admin-api/") || path.equals("/ws") || PAGES.contains(path));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", requestId);
        try (var ignored = MDC.putCloseable("requestId", requestId)) {
            try {
                chain.doFilter(request, response);
            } catch (IOException transport) {
                request.setAttribute(DiagnosticConfiguration.FAILURE, transport);
                throw new IOException("HTTP transport interrupted");
            } catch (ServletException | RuntimeException unexpected) {
                request.setAttribute(DiagnosticConfiguration.FAILURE, unexpected);
                if (response.isCommitted()) throw new ServletException("Request failed after response commit");
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            } finally {
                complete(request, response.getStatus(), start);
            }
        }
    }

    private static void complete(HttpServletRequest request, int status, long start) {
        Object problem = request.getAttribute(DiagnosticConfiguration.FAILURE);
        String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
        var entry = status >= 500 || (problem != null && status < 400) ? LOG.atError() : LOG.atInfo();
        entry.log("event=http_request method={} route={} status={} durationMs={} error={} source={}",
            method, route(request), status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
            problem instanceof Throwable ex ? ex.getClass().getSimpleName() : "-",
            problem instanceof Throwable ex ? FailureSite.of(ex) : "-");
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static String route(HttpServletRequest request) {
        String path = path(request);
        // Security may reject the request before MVC has selected a route.
        if (SECURITY_ROUTES.contains(path) || PAGES.contains(path)) return path;
        Object template = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (template instanceof String route && !route.equals("/**")) return route;
        return path.startsWith("/admin-api/") ? "/admin-api/**" : "/api/**";
    }
}
