package com.nexgenai.bootstrap;

import com.nexgenai.model.Admin;
import com.nexgenai.model.Candidate;
import com.nexgenai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Scanner;

@Component
@RequiredArgsConstructor
public class AdminInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        // Vérifier si un admin existe déjà
        if (userRepository.findByEmail("admin@nexgenai.com").isEmpty()) {
            System.out.println("\n==========================================");
            System.out.println("  CRÉATION DE L'ADMINISTRATEUR PAR DÉFAUT");
            System.out.println("==========================================");
            
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
            
            System.out.println("✅ Admin créé avec succès !");
            System.out.println("📧 Email: admin@nexgenai.com");
            System.out.println("🔑 Mot de passe: Admin123!");
            System.out.println("==========================================\n");
        }
      
    }
    
    

    

    
}