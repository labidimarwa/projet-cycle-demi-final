package com.nexgenai.config;

import com.nexgenai.service.AssessmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs once at startup to seed built-in psychometric models (DISC, Big Five, EQ-i, MBTI).
 *
 * Phase 2 refactoring: delegates to the unified {@link AssessmentService}
 * instead of the now-removed {@code JobTestService}.
 */
@Profile("!test")
@Component
@RequiredArgsConstructor
public class JobTestDataInitializer implements CommandLineRunner {

    private final AssessmentService assessmentService;

    @Override
    public void run(String... args) {
        assessmentService.initBuiltInModels();
    }
}

/* ─── SecurityConfig additions ────────────────────────────────────────────────
   Add these lines inside your existing SecurityConfig.securityFilterChain():

   .requestMatchers("/api/v1/job-tests/**").hasAnyRole("HR", "ADMIN")

   Full example placement (after your existing /api/v1/jobs/** rule):

   .requestMatchers("/api/v1/jobs/active").hasAnyRole("CANDIDATE", "HR", "ADMIN")
   .requestMatchers("/api/v1/jobs/**").hasAnyRole("HR", "ADMIN")
   .requestMatchers("/api/v1/job-tests/**").hasAnyRole("HR", "ADMIN")     ← ADD THIS
   .anyRequest().authenticated()

 * ─── application.properties additions ────────────────────────────────────────
   # Question images
   app.question.image-dir=uploads/questions
   app.base-url=http://localhost:8080

   # Multipart (increase if needed)
   spring.servlet.multipart.max-file-size=10MB
   spring.servlet.multipart.max-request-size=10MB

 * ─── Static resource serving for images ──────────────────────────────────────
   Add to your Spring Boot config class or a new WebMvcConfig:

   @Configuration
   public class WebMvcConfig implements WebMvcConfigurer {
       @Value("${app.question.image-dir:uploads/questions}")
       private String imageDir;

       @Override
       public void addResourceHandlers(ResourceHandlerRegistry registry) {
           registry.addResourceHandler("/uploads/questions/**")
                   .addResourceLocations("file:" + imageDir + "/");
       }
   }

   Then in AssessmentService.mapQuestion(), change imageUrl to:
   imageUrl = baseUrl + "/uploads/questions/" + filename;
   (simpler than the controller endpoint approach)
*/
