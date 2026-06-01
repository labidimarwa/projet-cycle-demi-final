package com.nexgenai.bootstrap;

import com.nexgenai.model.Admin;
import com.nexgenai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
@Profile("!test")

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        // Vérifier si un admin existe déjà
        if (userRepository.findByEmail("admin@nexgenai.com").isEmpty()) {
            log.info("==========================================");
            log.info("  CRÉATION DE L'ADMINISTRATEUR PAR DÉFAUT");
            log.info("==========================================");
            
            // Créer l'admin par défaut
            Admin admin = Admin.builder()
                    .firstName("Super")
                    .lastName("Admin")
                    .email("admin@nexgenai.com")
                    .password(passwordEncoder.encode("Admin123!"))
                    .isActive(true)
                    .createdAt(LocalDateTime.now())
                    .roleLevel("SUPER_ADMIN")
                    .build();

            userRepository.save(admin);

            log.info("Admin créé avec succès !");
            log.info("Email: admin@nexgenai.com");
            log.info("==========================================");
        }
      
    }
    
    

    

    
}