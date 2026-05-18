package com.nexgenai.service;

import com.nexgenai.dto.evaluator.EvaluatorSummaryDTO;
import com.nexgenai.dto.user.CreateUserRequest;
import com.nexgenai.dto.user.CreateUserResponse;
import com.nexgenai.dto.user.UserListResponse;
import com.nexgenai.model.*;
import java.util.List;
import java.util.stream.Collectors;
import com.nexgenai.repository.*;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository          userRepository;
    private final HRRepository            hrRepository;
    private final TechEvaluatorRepository techEvaluatorRepository;
    private final PasswordEncoder         passwordEncoder;
    private final EmailService            emailService;

    // ─── GET all HR users ─────────────────────────────────────────────────────

    public List<EvaluatorSummaryDTO> getAllHrUsers() {
        return hrRepository.findAll().stream()
                .map(hr -> {
                    EvaluatorSummaryDTO dto = new EvaluatorSummaryDTO();
                    dto.setId(hr.getId());
                    dto.setFullName(hr.getFirstName() + " " + hr.getLastName());
                    dto.setEmail(hr.getEmail());
                    dto.setDepartment(hr.getDepartment());
                    dto.setRole("HR");
                    return dto;
                })
                .collect(Collectors.toList());
    }

    // ─── GET all Admin users ──────────────────────────────────────────────────

    public List<EvaluatorSummaryDTO> getAllAdminUsers() {
        return userRepository.findAll().stream()
                .filter(u -> u instanceof Admin)
                .map(u -> {
                    EvaluatorSummaryDTO dto = new EvaluatorSummaryDTO();
                    dto.setId(u.getId());
                    dto.setFullName(u.getFirstName() + " " + u.getLastName());
                    dto.setEmail(u.getEmail());
                    dto.setRole("ADMIN");
                    return dto;
                })
                .collect(Collectors.toList());
    }

    // ─── GET all users (paginated) ────────────────────────────────────────────

    public Page<UserListResponse> getUsers(Pageable pageable) {
        return userRepository.findAll(pageable)
                .map(this::toUserListResponse);
    }

    // ─── CREATE user ──────────────────────────────────────────────────────────

    @Transactional
    public CreateUserResponse createUser(@Valid CreateUserRequest request) {
        log.info("Creating user: {} | role: {}", request.getEmail(), request.getRole());

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + request.getEmail());
        }

        User user = buildUserByRole(request);
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setCreatedAt(LocalDateTime.now());
        user.setActive(true);

        User saved = userRepository.save(user);
        log.info("User created with id: {}", saved.getId());

        sendWelcomeEmail(saved, request.getPassword());

        return CreateUserResponse.builder()
                .id(saved.getId())
                .firstName(saved.getFirstName())
                .lastName(saved.getLastName())
                .email(saved.getEmail())
                .role(saved.getUserType())
                .message("User created successfully. Credentials sent by email.")
                .build();
    }

    // ─── DELETE user ──────────────────────────────────────────────────────────

    @Transactional
    public void deleteUser(String id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        userRepository.delete(user);
        log.info("User deleted: {}", id);
    }

    // ─── TOGGLE active status ─────────────────────────────────────────────────

    @Transactional
    public void toggleUserStatus(String id, boolean active) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
        user.setActive(active);
        userRepository.save(user);
        log.info("User {} status set to: {}", id, active);
    }

    // ─── PRIVATE helpers ──────────────────────────────────────────────────────

    private User buildUserByRole(CreateUserRequest request) {
        return switch (request.getRole()) {
            case "HR" -> {
                HR hr = new HR();
                hr.setDepartment(request.getDepartment());
                hr.setPosition(request.getPosition());
                yield hr;
            }
            case "TECH_EVALUATOR" -> {
                TechEvaluator te = new TechEvaluator();
                te.setSpecialization(request.getSpecialization());
                te.setTitle(request.getTitle());
                te.setEmployeeId(request.getEmployeeId());
                te.setYearsOfExperience(request.getYearsOfExperience());
                te.setExpertiseLevel(request.getExpertiseLevel());
                te.setCurrentCompany(request.getCurrentCompany());
                te.setLinkedinUrl(request.getLinkedinUrl());
                te.setGithubUrl(request.getGithubUrl());
                te.setMaxEvaluationsPerDay(3);
                te.setEvaluationsToday(0);
                te.setTotalEvaluations(0);
                te.setAverageRating(0.0);
                te.setCanCreateTechnicalTests(true);
                te.setCanGradeTests(true);
                te.setCanConductInterviews(true);
                yield te;
            }
            default -> throw new IllegalArgumentException("Invalid role: " + request.getRole());
        };
    }

    private UserListResponse toUserListResponse(User user) {
        return UserListResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .userType(user.getUserType())
                .active(user.isActive())
                .createdAt(user.getCreatedAt())
                .lastLogin(user.getLastLogin())
                .build();
    }

    private void sendWelcomeEmail(User user, String plainPassword) {
        String subject = "Welcome to NexGen AI - Your Account Details";
        String content = String.format("""
            <html>
              <body style="font-family: Arial, sans-serif; padding: 20px;">
                <h2 style="color: #2b6cee;">Welcome to NexGen AI, %s %s!</h2>
                <p>Your account has been created successfully.</p>
                <div style="background:#f5f5f5;padding:15px;border-radius:5px;margin:20px 0;">
                  <h3 style="margin-top:0;">Login Credentials:</h3>
                  <p><strong>Email:</strong> %s</p>
                  <p><strong>Password:</strong> %s</p>
                  <p style="color:#666;font-size:12px;">Please change your password after first login.</p>
                </div>
                <p>Login: <a href="http://localhost:4200/auth/login" style="color:#2b6cee;">NexGen AI Portal</a></p>
                <hr style="border:none;border-top:1px solid #eee;margin:20px 0;">
                <p style="color:#999;font-size:12px;">This is an automated message, please do not reply.</p>
              </body>
            </html>
            """, user.getFirstName(), user.getLastName(), user.getEmail(), plainPassword);

        emailService.sendEmail(user.getEmail(), subject, content);
    }
}