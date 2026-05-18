// InterviewService.java
package com.nexgenai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexgenai.dto.interview.InterviewDtos.*;
import com.nexgenai.model.*;
import com.nexgenai.model.enums.StageProgressStatus;
import com.nexgenai.model.enums.StageType;
import com.nexgenai.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewService {

    private final InterviewRepository                interviewRepository;
    private final InterviewSlotRepository            slotRepository;
    private final JobRepository                      jobRepository;
    private final UserRepository                     userRepository;
    private final ApplicationStageProgressRepository stageProgressRepository;
    // Phase 2: unified TestSessionRepository (was TechnicalSessionRepository)
    // Field retained for potential future use but currently unused in this service.
    private final TestSessionRepository              testSessionRepository;
    private final ObjectMapper                       objectMapper;

    // ── Create interviews when a job is created ───────────────────────────────

    @Transactional
public void createInterviewsForJob(Job job) {
    if (job.getWorkflowStages() == null) return;

    for (WorkflowStage stage : job.getWorkflowStages()) {
        StageType type = stage.getStageType();
        if (type == null) continue;

        boolean isInterview = type == StageType.RH_INTERVIEW
                || type == StageType.TECHNICAL_INTERVIEW
                || type == StageType.ADMIN_INTERVIEW;
        if (!isInterview) continue;

        if (interviewRepository.findByWorkflowStageId(stage.getId()).isPresent()) continue;

        // ✅ Log what we're getting from the stage
        log.info("Creating interview for stage: name={}, assigneeId={}, assignedTo={}", 
            stage.getName(), stage.getAssigneeId(), stage.getAssignedTo());

        Interview interview = Interview.builder()
                .jobId(job.getId())
                .jobTitle(job.getTitle())
                .jobDepartment(job.getDepartment())
                .workflowStageId(stage.getId())
                .stageName(stage.getName())
                .stageType(type)
                .assigneeId(stage.getAssigneeId())   // ← could be null!
                .assigneeName(stage.getAssignedTo())
                .durationMinutes(60)
                .interviewsPerDay(4)
                .dayStartTime("09:00")
                .dayEndTime("18:00")
                .gridConfigured(false)
                .scheduleConfigured(false)
                .slotsGenerated(false)
                .build();

        interviewRepository.save(interview);
    }
}
    // ── Bootstrap: create missing interviews for all existing jobs ────────────

    @Transactional
    public void bootstrapInterviewsForAllJobs() {
        List<Job> jobs = jobRepository.findAll();
        for (Job job : jobs) {
            createInterviewsForJob(job);
        }
        log.info("Bootstrap: interviews checked/created for {} jobs", jobs.size());
    }

    // ── Get interviews for a user ─────────────────────────────────────────────

    public List<InterviewSummaryResponse> getInterviewsForUser(String userId) {
        
        log.info(">>> userId reçu : '{}'", userId);
        log.info(">>> userId length : {}", userId.length());
        
        interviewRepository.findAll().forEach(i -> {
            log.info(">>> interview assigneeId : '{}' length:{}", 
                i.getAssigneeId(), 
                i.getAssigneeId() != null ? i.getAssigneeId().length() : -1);
            log.info(">>> equals: {}", userId.equals(i.getAssigneeId()));
        });
        
        List<Interview> result = interviewRepository.findByAssigneeId(userId);
        log.info(">>> findByAssigneeId result size: {}", result.size());
        
        return result.stream().map(this::toSummary).collect(Collectors.toList());
    }

    // ── Configure schedule + evaluation grid ──────────────────────────────────

    @Transactional
    public InterviewSummaryResponse configure(String interviewId, InterviewConfigRequest req) {
        Interview interview = findInterview(interviewId);

        // Basic schedule fields
        interview.setStartDate(req.getStartDate());
        interview.setEndDate(req.getEndDate());
        interview.setDurationMinutes(req.getDurationMinutes() != null ? req.getDurationMinutes() : 60);
        interview.setInterviewsPerDay(req.getInterviewsPerDay() != null ? req.getInterviewsPerDay() : 4);
        interview.setScheduleConfigured(true);

        // Day working hours
        if (req.getDayStartTime() != null) interview.setDayStartTime(req.getDayStartTime());
        if (req.getDayEndTime()   != null) interview.setDayEndTime(req.getDayEndTime());

        // Excluded time ranges — stored as JSON array of "HH:mm-HH:mm" strings
        if (req.getExcludedHours() != null) {
            try {
                interview.setExcludedHoursJson(objectMapper.writeValueAsString(req.getExcludedHours()));
            } catch (Exception e) {
                log.warn("Could not serialize excluded hours: {}", e.getMessage());
            }
        }

        // Multi-assignee IDs
        if (req.getAssigneeIds() != null && !req.getAssigneeIds().isEmpty()) {
            try {
                interview.setAssigneeIdsJson(objectMapper.writeValueAsString(req.getAssigneeIds()));
                // Keep primary assignee as first in list (backward compat)
                String firstId = req.getAssigneeIds().get(0);
                userRepository.findById(firstId).ifPresent(u -> {
                    interview.setAssigneeId(firstId);
                    interview.setAssigneeName(u.getFirstName() + " " + u.getLastName());
                });
            } catch (Exception e) {
                log.warn("Could not serialize assignee IDs: {}", e.getMessage());
            }
        }

        // Evaluation grid
        if (req.getEvaluationGrid() != null && !req.getEvaluationGrid().isEmpty()) {
            try {
                interview.setEvaluationGridJson(objectMapper.writeValueAsString(req.getEvaluationGrid()));
                interview.setGridConfigured(true);
            } catch (Exception e) {
                log.warn("Could not serialize evaluation grid: {}", e.getMessage());
            }
        }

        interviewRepository.save(interview);
        return toSummary(interview);
    }

    // ── Generate slots (algorithm) ────────────────────────────────────────────

    @Transactional
    public List<SlotResponse> generateSlots(String interviewId) {
        Interview interview = findInterview(interviewId);

        if (interview.getStartDate() == null || interview.getEndDate() == null) {
            throw new RuntimeException("Interview schedule not configured yet");
        }

        // Delete previous slots
        slotRepository.deleteAll(slotRepository.findByInterviewId(interviewId));

        // Get eligible candidates
        List<String> candidateIds = getEligibleCandidates(interview);
        if (candidateIds.isEmpty()) {
            log.info("No eligible candidates for interview {}", interviewId);
            return List.of();
        }

        // Parse config
        List<String> excludedRanges = parseJsonList(interview.getExcludedHoursJson());
        List<String> assigneeIds    = parseJsonList(interview.getAssigneeIdsJson());

        // Fallback to primary assignee if none configured
        if (assigneeIds.isEmpty() && interview.getAssigneeId() != null) {
            assigneeIds = List.of(interview.getAssigneeId());
        }

        int duration = interview.getDurationMinutes() != null ? interview.getDurationMinutes() : 60;
        int perDay   = interview.getInterviewsPerDay() != null ? interview.getInterviewsPerDay() : 4;

        String dayStart = interview.getDayStartTime() != null ? interview.getDayStartTime() : "09:00";
        String dayEnd   = interview.getDayEndTime()   != null ? interview.getDayEndTime()   : "18:00";

        // Generate available time slots
        List<LocalDateTime> availableSlots = generateTimeSlots(
                interview.getStartDate(), interview.getEndDate(),
                duration, perDay,
                dayStart, dayEnd,
                excludedRanges
        );

        List<InterviewSlot> slots = new ArrayList<>();
        int slotIdx    = 0;
        int assigneeIdx = 0;

        for (String candidateId : candidateIds) {
            if (slotIdx >= availableSlots.size()) break;

            User user = userRepository.findById(candidateId).orElse(null);
            if (user == null) continue;

            // Round-robin over assignees
            String assigneeId   = assigneeIds.isEmpty() ? null : assigneeIds.get(assigneeIdx % assigneeIds.size());
            String assigneeName = null;
            if (assigneeId != null) {
                User assignee = userRepository.findById(assigneeId).orElse(null);
                if (assignee != null) assigneeName = assignee.getFirstName() + " " + assignee.getLastName();
            }
            assigneeIdx++;

            LocalDateTime start = availableSlots.get(slotIdx++);
            LocalDateTime end   = start.plusMinutes(duration);

            InterviewSlot slot = InterviewSlot.builder()
                    .interviewId(interviewId)
                    .candidateId(candidateId)
                    .candidateName(user.getFirstName() + " " + user.getLastName())
                    .candidateEmail(user.getEmail())
                    .assigneeId(assigneeId)
                    .assigneeName(assigneeName)
                    .slotStart(start)
                    .slotEnd(end)
                    .status(InterviewSlot.SlotStatus.SCHEDULED)
                    .decision("PENDING")
                    .build();

            slots.add(slotRepository.save(slot));
        }

        interview.setSlotsGenerated(true);
        interviewRepository.save(interview);

        log.info("Generated {} slots for interview {} (assignees: {})", slots.size(), interviewId, assigneeIds.size());
        return slots.stream().map(this::toSlotResponse).collect(Collectors.toList());
    }

    // ── Get slots ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SlotResponse> getSlots(String interviewId) {
        return slotRepository.findByInterviewId(interviewId).stream()
                .sorted(Comparator.comparing(InterviewSlot::getSlotStart))
                .map(this::toSlotResponse)
                .collect(Collectors.toList());
    }

    
    
    
    
    
    
    
 // InterviewService.java — assure-toi que cette méthode existe
    @Transactional(readOnly = true)
    public InterviewSummaryResponse getInterview(String interviewId) {
        return toSummary(findInterview(interviewId));
    }
    
    
    // ── Submit evaluation ─────────────────────────────────────────────────────

    @Transactional
    public SlotResponse submitEvaluation(String slotId, EvaluationSubmitRequest req) {
        InterviewSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new RuntimeException("Slot not found: " + slotId));

        try {
            slot.setEvaluationResultJson(objectMapper.writeValueAsString(req.getScores()));
        } catch (Exception e) {
            log.warn("Could not serialize evaluation: {}", e.getMessage());
        }

        slot.setComment(req.getComment());
        slot.setOverallScore(req.getOverallScore());
        slot.setDecision(req.getDecision());
        slot.setStatus(InterviewSlot.SlotStatus.COMPLETED);

        if ("ACCEPTED".equals(req.getDecision())) {
            advanceStageProgress(slot.getCandidateId(), findInterview(slot.getInterviewId()).getJobId());
        } else if ("REJECTED".equals(req.getDecision())) {
            rejectStageProgress(slot.getCandidateId(), findInterview(slot.getInterviewId()).getJobId());
        }

        return toSlotResponse(slotRepository.save(slot));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ALGORITHM: generate time slots
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Generates available LocalDateTime slots between startDate and endDate,
     * respecting:
     *  - dayStartTime / dayEndTime (working hours)
     *  - durationMinutes (slot length)
     *  - perDay (max slots per working day)
     *  - excludedRanges (list of "HH:mm-HH:mm" strings to skip)
     *  - weekends are skipped
     */
    private List<LocalDateTime> generateTimeSlots(
            LocalDate start,
            LocalDate end,
            int durationMinutes,
            int perDay,
            String dayStartStr,
            String dayEndStr,
            List<String> excludedRanges) {

        List<LocalDateTime> slots     = new ArrayList<>();
        List<LocalTime[]>   excluded  = parseExcludedRanges(excludedRanges);
        LocalTime            dayStart  = parseTime(dayStartStr, LocalTime.of(9, 0));
        LocalTime            dayEnd    = parseTime(dayEndStr,   LocalTime.of(18, 0));

        LocalDate current = start;

        while (!current.isAfter(end)) {
            DayOfWeek dow = current.getDayOfWeek();

            // Skip weekends
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                LocalTime time         = dayStart;
                int       slotsThisDay = 0;

                while (slotsThisDay < perDay) {
                    LocalTime slotEnd = time.plusMinutes(durationMinutes);

                    // Stop if slot would exceed day end
                    if (slotEnd.isAfter(dayEnd)) break;

                    // Check if this slot overlaps any excluded range
                    if (!overlapsExcluded(time, slotEnd, excluded)) {
                        slots.add(LocalDateTime.of(current, time));
                        slotsThisDay++;
                    }

                    time = slotEnd; // advance to next possible slot
                }
            }
            current = current.plusDays(1);
        }
        return slots;
    }

    /**
     * Returns true if [slotStart, slotEnd) overlaps any excluded [from, to) range.
     */
    private boolean overlapsExcluded(LocalTime slotStart, LocalTime slotEnd, List<LocalTime[]> excluded) {
        for (LocalTime[] range : excluded) {
            LocalTime exFrom = range[0];
            LocalTime exTo   = range[1];
            // Overlap condition: slotStart < exTo AND slotEnd > exFrom
            if (slotStart.isBefore(exTo) && slotEnd.isAfter(exFrom)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses a list of "HH:mm-HH:mm" strings into LocalTime pairs.
     */
    private List<LocalTime[]> parseExcludedRanges(List<String> ranges) {
        List<LocalTime[]> result = new ArrayList<>();
        for (String r : ranges) {
            if (r == null || !r.contains("-")) continue;
            String[] parts = r.split("-", 2);
            if (parts.length != 2) continue;
            try {
                LocalTime from = LocalTime.parse(parts[0].trim());
                LocalTime to   = LocalTime.parse(parts[1].trim());
                if (from.isBefore(to)) {
                    result.add(new LocalTime[]{from, to});
                }
            } catch (Exception e) {
                log.warn("Could not parse excluded range '{}': {}", r, e.getMessage());
            }
        }
        return result;
    }

    private LocalTime parseTime(String hhmm, LocalTime fallback) {
        if (hhmm == null || hhmm.isBlank()) return fallback;
        try {
            return LocalTime.parse(hhmm);
        } catch (Exception e) {
            return fallback;
        }
    }

    // ── Eligible candidates ───────────────────────────────────────────────────

    private List<String> getEligibleCandidates(Interview interview) {
        List<ApplicationStageProgress> rows =
                stageProgressRepository.findByJobIdAndStageType(
                        interview.getJobId(), interview.getStageType().name());

        return rows.stream()
                .filter(r -> r.getStatus() == StageProgressStatus.IN_PROGRESS)
                .map(ApplicationStageProgress::getCandidateId)
                .distinct()
                .collect(Collectors.toList());
    }

    // ── Stage progress ────────────────────────────────────────────────────────

    private void advanceStageProgress(String candidateId, String jobId) {
        List<ApplicationStageProgress> stages = stageProgressRepository
                .findByCandidateIdAndJobId(candidateId, jobId).stream()
                .sorted(Comparator.comparing(ApplicationStageProgress::getStageOrder))
                .collect(Collectors.toList());

        ApplicationStageProgress current = stages.stream()
                .filter(s -> s.getStatus() == StageProgressStatus.IN_PROGRESS)
                .findFirst().orElse(null);

        if (current != null) {
            current.setStatus(StageProgressStatus.COMPLETED);
            current.setCompletedAt(LocalDateTime.now());
            stageProgressRepository.save(current);

            stages.stream()
                    .filter(s -> s.getStageOrder() > current.getStageOrder())
                    .findFirst()
                    .ifPresent(next -> {
                        next.setStatus(StageProgressStatus.IN_PROGRESS);
                        next.setStartedAt(LocalDateTime.now());
                        stageProgressRepository.save(next);
                    });
        }
    }

    private void rejectStageProgress(String candidateId, String jobId) {
        stageProgressRepository.findByCandidateIdAndJobId(candidateId, jobId).stream()
                .filter(s -> s.getStatus() == StageProgressStatus.IN_PROGRESS)
                .findFirst()
                .ifPresent(s -> {
                    s.setStatus(StageProgressStatus.REJECTED);
                    s.setCompletedAt(LocalDateTime.now());
                    stageProgressRepository.save(s);
                });
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private InterviewSummaryResponse toSummary(Interview i) {
        List<String>       excluded    = parseJsonList(i.getExcludedHoursJson());
        List<String>       assigneeIds = parseJsonList(i.getAssigneeIdsJson());
        List<AssigneeDTO>  assignees   = resolveAssignees(assigneeIds);

        int total     = slotRepository.countByInterviewId(i.getId());
        int completed = slotRepository.countByInterviewIdAndStatus(i.getId(), InterviewSlot.SlotStatus.COMPLETED);

        return InterviewSummaryResponse.builder()
                .id(i.getId())
                .jobId(i.getJobId())
                .jobTitle(i.getJobTitle())
                .jobDepartment(i.getJobDepartment())
                .workflowStageId(i.getWorkflowStageId())
                .stageName(i.getStageName())
                .stageType(i.getStageType())
                .assigneeId(i.getAssigneeId())
                .assigneeName(i.getAssigneeName())
                .startDate(i.getStartDate())
                .endDate(i.getEndDate())
                .dayStartTime(i.getDayStartTime() != null ? i.getDayStartTime() : "09:00")
                .dayEndTime(i.getDayEndTime() != null ? i.getDayEndTime() : "18:00")
                .durationMinutes(i.getDurationMinutes())
                .interviewsPerDay(i.getInterviewsPerDay())
                .gridConfigured(i.getGridConfigured())
                .scheduleConfigured(i.getScheduleConfigured())
                .slotsGenerated(i.getSlotsGenerated())
                .totalCandidates(total)
                .completedInterviews(completed)
                .excludedHours(excluded)
                .assignees(assignees)
                .build();
    }

    private SlotResponse toSlotResponse(InterviewSlot s) {
        return SlotResponse.builder()
                .id(s.getId())
                .candidateId(s.getCandidateId())
                .candidateName(s.getCandidateName())
                .candidateEmail(s.getCandidateEmail())
                .assigneeId(s.getAssigneeId())
                .assigneeName(s.getAssigneeName())
                .slotStart(s.getSlotStart() != null ? s.getSlotStart().toString() : null)
                .slotEnd(s.getSlotEnd()     != null ? s.getSlotEnd().toString()   : null)
                .status(s.getStatus()       != null ? s.getStatus().name()        : null)
                .overallScore(s.getOverallScore())
                .decision(s.getDecision())
                .evaluated(s.getStatus() == InterviewSlot.SlotStatus.COMPLETED)
                .build();
    }

    /**
     * Resolves a list of user IDs into AssigneeDTO objects.
     * Missing users are silently skipped.
     */
    private List<AssigneeDTO> resolveAssignees(List<String> ids) {
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        return ids.stream()
                .map(id -> userRepository.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .map(u -> AssigneeDTO.builder()
                        .id(u.getId())
                        .fullName(u.getFirstName() + " " + u.getLastName())
                        .email(u.getEmail())
                        .role(u.getClass().getSimpleName().toUpperCase())
                        .build())
                .collect(Collectors.toList());
    }
    @Transactional
    public void deleteInterviewsForJob(Job job) {
        interviewRepository.deleteAllByJobId(job.getId());
    }


    // ── JSON helpers ──────────────────────────────────────────────────────────

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Interview findInterview(String id) {
        return interviewRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Interview not found: " + id));
    }
}