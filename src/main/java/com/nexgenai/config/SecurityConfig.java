package com.nexgenai.config;

import com.nexgenai.security.JwtAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String ROLE_ADMIN     = "ADMIN";
    private static final String ROLE_HR        = "HR";
    private static final String ROLE_CANDIDATE = "CANDIDATE";
    private static final String ROLE_EVALUATOR = "EVALUATOR";
    private static final String PATH_JOBS      = "/jobs/**";
    private static final String PATH_JOB_TESTS = "/job-tests/**";
    private static final String PATH_INTERVIEWS = "/interviews/**";

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
            // OWASP Secure Headers (complète les defaults Spring Security)
            .headers(headers -> {
                headers.referrerPolicy(rp -> rp.policy(
                    ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                headers.permissionsPolicy(pp -> pp.policy(
                    "camera=(), microphone=(), geolocation=()"));
                headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; frame-ancestors 'none'; object-src 'none'"));
            })

            // Désactiver CSRF (API REST stateless)
            .csrf(csrf -> csrf.disable())

            // Activer CORS géré par Spring Security
            .cors(Customizer.withDefaults())
            .securityContext(context -> context.requireExplicitSave(false))

            // Gestion des exceptions (401 / 403)
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(jwtAuthenticationEntryPoint) // 401
                .accessDeniedHandler(accessDeniedHandler())             // 403
            )

            // Stateless (JWT)
            .sessionManagement(session -> session

                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Configuration des autorisations — RBAC (OWASP A01)
            .authorizeHttpRequests(auth -> auth
                // Public — no auth required
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // Admin only
                .requestMatchers("/admin/**").hasRole(ROLE_ADMIN)

                // Job listings — public GET, write requires HR or ADMIN
                .requestMatchers(HttpMethod.GET,    PATH_JOBS).permitAll()
                .requestMatchers(HttpMethod.POST,   PATH_JOBS).hasAnyRole(ROLE_HR, ROLE_ADMIN)
                .requestMatchers(HttpMethod.PUT,    PATH_JOBS).hasAnyRole(ROLE_HR, ROLE_ADMIN)
                .requestMatchers(HttpMethod.DELETE, PATH_JOBS).hasAnyRole(ROLE_HR, ROLE_ADMIN)
                .requestMatchers(HttpMethod.PATCH,  PATH_JOBS).hasAnyRole(ROLE_HR, ROLE_ADMIN)
                .requestMatchers(HttpMethod.POST, "/api/v1/jobs/*/remote-link").hasAnyRole(ROLE_HR, ROLE_ADMIN)

                // Job-tests — HR/ADMIN configure, candidates can read their own
                .requestMatchers(HttpMethod.GET,    PATH_JOB_TESTS).authenticated()
                .requestMatchers(HttpMethod.POST,   PATH_JOB_TESTS).hasAnyRole(ROLE_HR, ROLE_ADMIN)
                .requestMatchers(HttpMethod.PUT,    PATH_JOB_TESTS).hasAnyRole(ROLE_HR, ROLE_ADMIN)
                .requestMatchers(HttpMethod.DELETE, PATH_JOB_TESTS).hasAnyRole(ROLE_HR, ROLE_ADMIN)
                .requestMatchers(HttpMethod.PATCH,  PATH_JOB_TESTS).hasAnyRole(ROLE_HR, ROLE_ADMIN)

                // Notifications — all authenticated users
                .requestMatchers("/notifications/**").authenticated()

                // Candidate portal — authenticated candidates only
                .requestMatchers("/api/v1/candidate/matches/*/compute").hasRole(ROLE_CANDIDATE)
                .requestMatchers("/api/v1/candidate/**").hasRole(ROLE_CANDIDATE)
                .requestMatchers(HttpMethod.GET, "/api/v1/candidate/applications/*/stages").hasRole(ROLE_CANDIDATE)

                // Interviews — HR/ADMIN manage, evaluators read
                .requestMatchers(HttpMethod.GET,   PATH_INTERVIEWS).hasAnyRole(ROLE_HR, ROLE_ADMIN, ROLE_EVALUATOR)
                .requestMatchers(HttpMethod.POST,  PATH_INTERVIEWS).hasAnyRole(ROLE_HR, ROLE_ADMIN)
                .requestMatchers(HttpMethod.PUT,   PATH_INTERVIEWS).hasAnyRole(ROLE_HR, ROLE_ADMIN)
                .requestMatchers(HttpMethod.PATCH, PATH_INTERVIEWS).hasAnyRole(ROLE_HR, ROLE_ADMIN, ROLE_EVALUATOR)

                // Evaluators management
                .requestMatchers("/api/v1/evaluators/**").hasAnyRole(ROLE_HR, ROLE_ADMIN)
                .requestMatchers(HttpMethod.PATCH, "/api/v1/hr/candidates/*/jobs/*/stages/*").hasAnyRole(ROLE_HR, ROLE_ADMIN)

                // HR matching (MatchingController) — RH et Admin uniquement
                .requestMatchers("/hr/**").hasAnyRole(ROLE_HR, ROLE_ADMIN)

                // Everything else requires authentication
                .anyRequest().authenticated()
            )

            // Ajouter le filtre JWT
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // 🔐 Gestion 403 propre
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Access Denied\"}");
        };
    }

    // 🔐 CORS géré par Security (IMPORTANT)
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:4200"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH",  "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    // 🔐 Password Encoder
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 🔐 Authentication Manager
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
