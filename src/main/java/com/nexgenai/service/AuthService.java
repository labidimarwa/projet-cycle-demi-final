package com.nexgenai.service;

import com.nexgenai.dto.LoginRequest;
import com.nexgenai.dto.LoginResponse;
import com.nexgenai.dto.RegisterRequest;
import com.nexgenai.dto.RegisterResponse;
import com.nexgenai.model.Candidate;
import com.nexgenai.model.HR;
import com.nexgenai.model.TechEvaluator;
import com.nexgenai.model.User;
import com.nexgenai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository        userRepository;
    private final JwtService            jwtService;
    private final PasswordEncoder       passwordEncoder;   // ← injecté via SecurityConfig

    // ─── Login ────────────────────────────────────────────────────────────────
    @Transactional
    public LoginResponse login(LoginRequest request) {

        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        User user = userRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> new RuntimeException("User not found"));

        userRepository.updateLastLogin(user.getEmail(), LocalDateTime.now());

        String jwtToken    = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        LoginResponse.LoginResponseBuilder builder = LoginResponse.builder()
            .token(jwtToken)
            .refreshToken(refreshToken)
            .id(user.getId())
            .email(user.getEmail())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .userType(user.getUserType())
            .expiresIn(jwtService.getJwtExpiration());

        if (user instanceof Candidate candidate) {
            builder.currentPosition(candidate.getCurrentPosition())
                   .yearsOfExperience(candidate.getYearsOfExperience())
                   .educationLevel(candidate.getEducationLevel());
        } else if (user instanceof HR hr) {
            builder.department(hr.getDepartment())
                   .position(hr.getPosition());
        } else if (user instanceof TechEvaluator evaluator) {
            builder.specialization(evaluator.getSpecialization())
                   .expertiseLevel(evaluator.getExpertiseLevel())
                   .title(evaluator.getTitle());
        }

        return builder.build();
    }

    // ─── Register Candidat ────────────────────────────────────────────────────
    @Transactional
    public RegisterResponse registerCandidate(RegisterRequest request) {

        // 1. Vérifier unicité de l'email
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Un compte existe déjà avec cet email : " + request.getEmail());
        }

        // 2. Créer le Candidate (sous-classe de User)
        Candidate candidate = Candidate.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .email(request.getEmail())
            .password(passwordEncoder.encode(request.getPassword()))
            .isActive(true)
            .createdAt(LocalDateTime.now())
            .build();

        // 3. Sauvegarder
        Candidate saved = (Candidate) userRepository.save(candidate);
        System.out.println("✅ Candidat créé : " + saved.getId() + " — " + saved.getEmail());

        // 4. Générer les tokens JWT
        String token        = jwtService.generateToken(saved);
        String refreshToken = jwtService.generateRefreshToken(saved);

        return RegisterResponse.builder()
            .token(token)
            .refreshToken(refreshToken)
            .id(saved.getId())
            .email(saved.getEmail())
            .firstName(saved.getFirstName())
            .lastName(saved.getLastName())
            .userType(saved.getUserType())
            .expiresIn(jwtService.getJwtExpiration())
            .build();
    }
}