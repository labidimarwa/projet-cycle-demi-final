package com.nexgenai.controller;

import com.nexgenai.dto.evaluator.EvaluatorSummaryDTO;
import com.nexgenai.service.EvaluatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;



import com.nexgenai.dto.evaluator.EvaluatorDashboardDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/evaluators")
@CrossOrigin(origins = "http://localhost:4200")
public class EvaluatorController {

    private final EvaluatorService evaluatorService;

    public EvaluatorController(EvaluatorService evaluatorService) {
        this.evaluatorService = evaluatorService;
    }

    /**
     * GET /evaluators/by-department?department=Engineering
     * Returns TechEvaluators whose specialization matches the job's department.
     */
    @GetMapping("/by-department")
    public ResponseEntity<List<EvaluatorSummaryDTO>> getByDepartment(
            @RequestParam String department) {
        return ResponseEntity.ok(evaluatorService.getEvaluatorsByDepartment(department));
    }

 

    
    
    @GetMapping("/dashboard")
    public ResponseEntity<EvaluatorDashboardDto> getDashboard(
            @AuthenticationPrincipal UserDetails user) {
        return ResponseEntity.ok(evaluatorService.buildDashboard(user.getUsername()));
    }
}