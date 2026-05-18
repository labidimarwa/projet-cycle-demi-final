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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
            // Désactiver CSRF (API REST stateless)
            .csrf(csrf -> csrf.disable())

            // Activer CORS géré par Spring Security
            .cors(Customizer.withDefaults())

            // Gestion des exceptions (401 / 403)
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(jwtAuthenticationEntryPoint) // 401
                .accessDeniedHandler(accessDeniedHandler())             // 403
            )

            // Stateless (JWT)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Configuration des autorisations
            .authorizeHttpRequests(auth -> auth
            		.requestMatchers("/auth/**").permitAll()
            		.requestMatchers("/admin/**").hasRole("ADMIN")
            		.requestMatchers("/jobs/**").permitAll()
            		.requestMatchers("/job-tests/**").permitAll()
            		.requestMatchers("/candidate/**").permitAll()
            		.requestMatchers("/interviews/**").permitAll()
            		.requestMatchers("/api/v1/evaluators/**").hasAnyRole("HR", "ADMIN")
            		  .requestMatchers(HttpMethod.POST, "/api/v1/jobs/*/remote-link").hasAnyRole("HR", "ADMIN")
                .requestMatchers("/error").permitAll()
                
                .requestMatchers(HttpMethod.GET,   "/api/v1/candidate/applications/*/stages").hasRole("CANDIDATE")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/hr/candidates/*/jobs/*/stages/*").hasAnyRole("HR","ADMIN")
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // Preflight CORS
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
