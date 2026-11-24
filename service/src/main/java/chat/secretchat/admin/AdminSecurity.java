package chat.secretchat.admin;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.Assert;

@Configuration
class AdminSecurity {
    @Bean UserDetailsService administrators(@Value("${secretchat.admin.username}") String username,
                                           @Value("${secretchat.admin.password}") String password, Environment env) {
        Assert.hasText(username, "Configure the console administrator username");
        Assert.hasText(password, "Configure SECRETCHAT_ADMIN_PASSWORD before starting");
        var users = new InMemoryUserDetailsManager();
        addUser(users, username, password, "ADMIN");
        optionalUser(users, env, "REVIEWER");
        optionalUser(users, env, "OPS");
        return users;
    }

    private void optionalUser(InMemoryUserDetailsManager users, Environment env, String role) {
        String secret = env.getProperty("SECRETCHAT_" + role + "_PASSWORD", "");
        if (!secret.isBlank()) addUser(users, env.getRequiredProperty("SECRETCHAT_" + role + "_USERNAME"), secret, role);
    }

    private void addUser(InMemoryUserDetailsManager users,String name,String password,String role) {
        users.createUser(User.withUsername(name).password("{bcrypt}" + new BCryptPasswordEncoder().encode(password))
            .roles(role).build());
    }

    @Bean @Order(1) SecurityFilterChain deviceApi(HttpSecurity http) throws Exception {
        // Device endpoints validate a revocable device bearer. Browser admin cookies confer no device identity.
        return http.securityMatcher("/api/**", "/ws").csrf(csrf -> csrf.disable())
            .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(routes -> routes.anyRequest().permitAll()).build();
    }

    @Order(2)
    @Bean SecurityFilterChain adminFilter(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(routes -> routes
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/console", "/console/login.html", "/console/console.css", "/console/login.js",
                    "/console/client.js", "/admin-api/session", "/admin-api/login").permitAll()
                .requestMatchers("/console/**", "/admin-api/me", "/admin-api/status", "/admin-api/queue").hasAnyRole("ADMIN","OPS","REVIEWER")
                .requestMatchers("/admin-api/reports", "/admin-api/reports/**").hasAnyRole("ADMIN","REVIEWER")
                .requestMatchers("/admin-api/**").hasRole("ADMIN")
                .anyRequest().denyAll())
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, exception) -> {
                if (req.getServletPath().startsWith("/console/")) {
                    res.setStatus(HttpServletResponse.SC_FOUND);
                    res.setHeader("Location", "login.html");
                } else {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                }
            }))
            .formLogin(login -> login.loginPage("/console/login.html").loginProcessingUrl("/admin-api/login")
                .successHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT))
                .failureHandler((req, res, error) -> res.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
            .logout(logout -> logout.logoutUrl("/admin-api/logout")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT)))
            .build();
    }
}
