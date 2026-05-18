package com.nexgenai.controller;

import com.nexgenai.dto.LoginRequest;
import com.nexgenai.dto.LoginResponse;
import com.nexgenai.dto.RegisterRequest;
import com.nexgenai.dto.RegisterResponse;
import com.nexgenai.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200")
public class AuthController {

    private final AuthService authService;

    // ─── Login ────────────────────────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    // ─── Register Candidat ────────────────────────────────────────────────────
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {

        // Vérification concordance des mots de passe
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", "Les mots de passe ne correspondent pas"));
        }

        RegisterResponse response = authService.registerCandidate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ─── Utils ────────────────────────────────────────────────────────────────
    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("API is working!");
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("API is healthy!");
    }
}