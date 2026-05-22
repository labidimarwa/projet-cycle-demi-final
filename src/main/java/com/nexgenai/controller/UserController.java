package com.nexgenai.controller;

import com.nexgenai.dto.evaluator.EvaluatorSummaryDTO;
import com.nexgenai.dto.user.CreateUserRequest;
import com.nexgenai.dto.user.CreateUserResponse;
import com.nexgenai.dto.user.UserListResponse;
import com.nexgenai.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // ─── GET all users (admin only) ───────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<UserListResponse>> getUsers(
            @RequestParam(defaultValue = "0")          int    page,
            @RequestParam(defaultValue = "10")         int    size,
            @RequestParam(defaultValue = "createdAt")  String sortBy,
            @RequestParam(defaultValue = "desc")       String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(userService.getUsers(pageable));
    }

    // ─── GET all HR users ─────────────────────────────────────────────────────

    @GetMapping("/hr")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<EvaluatorSummaryDTO>> getAllHr() {
        return ResponseEntity.ok(userService.getAllHrUsers());
    }

    // ─── GET all Admin users ──────────────────────────────────────────────────

    @GetMapping("/admins")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<EvaluatorSummaryDTO>> getAllAdmins() {
        return ResponseEntity.ok(userService.getAllAdminUsers());
    }

    // ─── POST create user (admin only) ────────────────────────────────────────
    @PostMapping("/create")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreateUserResponse> createUser(
            @Valid @RequestBody CreateUserRequest request
    ) {
        return new ResponseEntity<>(userService.createUser(request), HttpStatus.CREATED);
    }

    // ─── DELETE user (admin only) ─────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    // ─── PATCH toggle active status (admin only) ──────────────────────────────

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> toggleStatus(
            @PathVariable String id,
            @RequestBody Map<String, Boolean> body
    ) {
        userService.toggleUserStatus(id, body.get("active"));
        return ResponseEntity.ok().build();
    }
}