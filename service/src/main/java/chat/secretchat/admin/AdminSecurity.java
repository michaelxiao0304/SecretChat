package chat.secretchat.admin;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                                           @Value("${secretchat.admin.password}") String password) {
        Assert.hasText(username, "Configure the console administrator username");
        Assert.hasText(password, "Configure SECRETCHAT_ADMIN_PASSWORD before starting");
        var admin = User.withUsername(username)
            .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean SecurityFilterChain adminFilter(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(routes -> routes
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/console", "/console/login.html", "/console/console.css", "/console/login.js",
                    "/console/client.js", "/admin-api/session", "/admin-api/login").permitAll()
                .requestMatchers("/console/**", "/admin-api/**").hasRole("ADMIN")
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
