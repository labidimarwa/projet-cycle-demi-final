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

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewService {

    private final InterviewRepository                interviewRepository;
    private final InterviewSlotRepository            slotRepository;
    private final JobRepository                      jobRepository;
    private final UserRepository                     userRepository;
    private final ApplicationStageProgressRepository stageProgressRepository;
    private final WorkflowStageRepository            workflowStageRepository;
    private final TestSessionRepository              testSessionRepository;
    private final ObjectMapper                       objectMapper;

    private static final String TIME_START       = "09:00";
    private static final String TIME_END         = "18:00";
    private static final String STATUS_NOT_CONFIGURED = "NOT_CONFIGURED";
    private static final String STATUS_CLOSED    = "CLOSED";
    private static final String DECISION_PENDING = "PENDING";
    private static final String DECISION_ACCEPTED = "ACCEPTED";
    private static final String DECISION_REJECTED = "REJECTED";

    // ══════════════════════════════════════════════════════════════════════════
    // CREATE
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public void createInterviewsForJob(Job job) {
        if (job.getWorkflowStages() == null) return;

        for (WorkflowStage stage : job.getWorkflowStages()) {
            createInterviewForStageIfNeeded(job, stage);
        }
    }

    private void createInterviewForStageIfNeeded(Job job, WorkflowStage stage) {
        StageType type = stage.getStageType();
        if (type == null) return;
        boolean isInterview = type == StageType.RH_INTERVIEW
                || type == StageType.TECHNICAL_INTERVIEW
                || type == StageType.ADMIN_INTERVIEW;
        if (!isInterview) return;
        if (interviewRepository.findByWorkflowStageId(stage.getId()).isPresent()) return;

        log.info("Creating interview for stage: name={}, assigneeId={}", stage.getName(), stage.getAssigneeId());
        Interview interview = Interview.builder()
                .jobId(job.getId())
                .jobTitle(job.getTitle())
                .jobDepartment(job.getDepartment())
                .workflowStageId(stage.getId())
                .stageName(stage.getName())
                .stageType(type)
                .assigneeId(stage.getAssigneeId())
                .assigneeName(stage.getAssignedTo())
                .durationMinutes(60)
                .interviewsPerDay(4)
                .dayStartTime(TIME_START)
                .dayEndTime(TIME_END)
                .gridConfigured(false)
                .scheduleConfigured(false)
                .slotsGenerated(false)
                .phaseStatus(STATUS_NOT_CONFIGURED)
                .build();
        interviewRepository.save(interview);
    }

    @Transactional
    public void bootstrapInterviewsForAllJobs() {
        List<Job> jobs = jobRepository.findAll();
        for (Job job : jobs) {
            createInterviewsForJob(job);
        }
        log.info("Bootstrap: interviews checked/created for {} jobs", jobs.size());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // READ
    // ══════════════════════════════════════════════════════════════════════════

    public List<InterviewSummaryResponse> getInterviewsForUser(String userId) {
        List<Interview> result = interviewRepository.findByAssigneeId(userId);
        return result.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public InterviewSummaryResponse getInterview(String interviewId) {
        return toSummary(findInterview(interviewId));
    }

    @Transactional(readOnly = true)
    public List<SlotResponse> getSlots(String interviewId) {
        return slotRepository.findByInterviewId(interviewId).stream()
                .sorted(Comparator.comparing(InterviewSlot::getSlotStart))
                .map(this::toSlotResponse)
                .toList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE STATUS OVERVIEW
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the status of all 3 interview phases for a given job,
     * including blocked dates and next-phase eligibility.
     */
    @Transactional(readOnly = true)
    public JobPhasesStatusResponse getJobPhases(String jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        List<Interview> rhList   = interviewRepository.findByJobIdAndStageType(jobId, StageType.RH_INTERVIEW);
        List<Interview> techList = interviewRepository.findByJobIdAndStageType(jobId, StageType.TECHNICAL_INTERVIEW);
        List<Interview> admList  = interviewRepository.findByJobIdAndStageType(jobId, StageType.ADMIN_INTERVIEW);

        Interview rh   = rhList.isEmpty()   ? null : rhList.get(0);
        Interview tech = techList.isEmpty() ? null : techList.get(0);
        Interview adm  = admList.isEmpty()  ? null : admList.get(0);

        boolean rhClosed   = rh   != null && STATUS_CLOSED.equals(rh.getPhaseStatus());
        boolean techClosed = tech != null && STATUS_CLOSED.equals(tech.getPhaseStatus());

        // Occupied dates from RH
        List<LocalDate> rhOccupied = new ArrayList<>();
        if (rh != null) {
            rhOccupied = slotRepository.findOccupiedDatesByInterviewIds(List.of(rh.getId()));
        }

        // Occupied dates from Technical
        List<LocalDate> techOccupied = new ArrayList<>();
        if (tech != null) {
            techOccupied = slotRepository.findOccupiedDatesByInterviewIds(List.of(tech.getId()));
        }

        // Earliest start for Technical = day after RH computed end (or today)
        LocalDate earliestTech = LocalDate.now();
        if (rh != null && rh.getComputedEndDateTime() != null) {
            earliestTech = rh.getComputedEndDateTime().toLocalDate().plusDays(1);
        }

        // Earliest start for Admin = day after Technical computed end
        LocalDate earliestAdm = earliestTech;
        if (tech != null && tech.getComputedEndDateTime() != null) {
            earliestAdm = tech.getComputedEndDateTime().toLocalDate().plusDays(1);
        }

        return JobPhasesStatusResponse.builder()
                .jobId(jobId)
                .jobTitle(job.getTitle())
                .rhInterview(rh   != null ? toSummary(rh)   : null)
                .technicalInterview(tech != null ? toSummary(tech) : null)
                .adminInterview(adm  != null ? toSummary(adm)  : null)
                .canConfigureTechnical(rhClosed)
                .canConfigureAdmin(rhClosed && techClosed)
                .earliestTechnicalStart(earliestTech)
                .earliestAdminStart(earliestAdm)
                .rhOccupiedDates(rhOccupied)
                .technicalOccupiedDates(techOccupied)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONFIGURE
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public InterviewSummaryResponse configure(String interviewId, InterviewConfigRequest req) {
        Interview interview = findInterview(interviewId);

        // Validate phase prerequisites before allowing configuration
        validatePhasePrerequisites(interview);

        interview.setStartDate(req.getStartDate());
        interview.setEndDate(req.getEndDate());
        interview.setDurationMinutes(req.getDurationMinutes() != null ? req.getDurationMinutes() : 60);
        interview.setInterviewsPerDay(req.getInterviewsPerDay() != null ? req.getInterviewsPerDay() : 4);
        interview.setScheduleConfigured(true);

        if (req.getDayStartTime() != null) interview.setDayStartTime(req.getDayStartTime());
        if (req.getDayEndTime()   != null) interview.setDayEndTime(req.getDayEndTime());

        if (req.getExcludedHours() != null) {
            try {
                interview.setExcludedHoursJson(objectMapper.writeValueAsString(req.getExcludedHours()));
            } catch (Exception e) {
                log.warn("Could not serialize excluded hours: {}", e.getMessage());
            }
        }

        if (req.getAssigneeIds() != null && !req.getAssigneeIds().isEmpty()) {
            try {
                interview.setAssigneeIdsJson(objectMapper.writeValueAsString(req.getAssigneeIds()));
                String firstId = req.getAssigneeIds().get(0);
                userRepository.findById(firstId).ifPresent(u -> {
                    interview.setAssigneeId(firstId);
                    interview.setAssigneeName(u.getFirstName() + " " + u.getLastName());
                });
            } catch (Exception e) {
                log.warn("Could not serialize assignee IDs: {}", e.getMessage());
            }
        }

        if (req.getEvaluationGrid() != null && !req.getEvaluationGrid().isEmpty()) {
            try {
                interview.setEvaluationGridJson(objectMapper.writeValueAsString(req.getEvaluationGrid()));
                interview.setGridConfigured(true);
            } catch (Exception e) {
                log.warn("Could not serialize evaluation grid: {}", e.getMessage());
            }
        }

        if (STATUS_NOT_CONFIGURED.equals(interview.getPhaseStatus())) {
            interview.setPhaseStatus("CONFIGURED");
        }

        interviewRepository.save(interview);
        return toSummary(interview);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GENERATE SLOTS — core parallel scheduling algorithm
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Generates slots with full parallel support:
     * - RH_INTERVIEW:         X HR managers interview simultaneously → X slots per time point
     * - TECHNICAL_INTERVIEW:  Must start after RH closed; no RH date reuse; distributed across N evaluators
     * - ADMIN_INTERVIEW:      Must start after both RH and Technical closed; only Technical-accepted candidates
     */
    @Transactional
    public List<SlotResponse> generateSlots(String interviewId) {
        Interview interview = findInterview(interviewId);

        if (interview.getStartDate() == null || interview.getEndDate() == null) {
            throw new IllegalStateException("Interview schedule not configured yet");
        }

        // Phase-specific prerequisites
        validateGenerateSlotsPrerequisites(interview);

        // Delete previous slots
        slotRepository.deleteAll(slotRepository.findByInterviewId(interviewId));

        // Candidates eligible for this phase
        List<String> candidateIds = getEligibleCandidates(interview);
        if (candidateIds.isEmpty()) {
            log.info("No eligible candidates for interview {}", interviewId);
            interview.setSlotsGenerated(true);
            interview.setPhaseStatus("IN_PROGRESS");
            interviewRepository.save(interview);
            return List.of();
        }

        // Assignees (HR managers / evaluators / admins)
        List<String> assigneeIds = parseJsonList(interview.getAssigneeIdsJson());
        if (assigneeIds.isEmpty() && interview.getAssigneeId() != null) {
            assigneeIds = List.of(interview.getAssigneeId());
        }
        int parallelism = assigneeIds.isEmpty() ? 1 : assigneeIds.size();

        // Config
        List<String> excludedRanges = parseJsonList(interview.getExcludedHoursJson());
        int duration  = interview.getDurationMinutes() != null ? interview.getDurationMinutes() : 60;
        int perDay    = interview.getInterviewsPerDay() != null ? interview.getInterviewsPerDay() : 4;
        String dayStart = interview.getDayStartTime() != null ? interview.getDayStartTime() : TIME_START;
        String dayEnd   = interview.getDayEndTime()   != null ? interview.getDayEndTime()   : TIME_END;

        // Dates that are blocked by a previous phase
        Set<LocalDate> blockedDates = getBlockedDatesForPhase(interview);

        // Generate ordered time slots (one per "round"; each round fills all parallel slots)
        SlotConfig slotCfg = new SlotConfig(duration, perDay, dayStart, dayEnd, excludedRanges);
        List<LocalDateTime> rounds = generateRounds(
                interview.getStartDate(), interview.getEndDate(),
                slotCfg, blockedDates);

        // Preload assignee names
        Map<String, String> assigneeNames = loadAssigneeNames(assigneeIds);

        // Build slots: for each round, assign up to `parallelism` candidates
        List<InterviewSlot> slots = new ArrayList<>();
        int candidateIdx = 0;

        for (LocalDateTime roundStart : rounds) {
            if (candidateIdx >= candidateIds.size()) {
                break;
            }
            LocalDateTime roundEnd = roundStart.plusMinutes(duration);
            for (int i = 0; i < parallelism && candidateIdx < candidateIds.size(); i++) {
                String candidateId = candidateIds.get(candidateIdx++);
                User candidate = userRepository.findById(candidateId).orElse(null);
                if (candidate == null) {
                    continue;
                }

                String assigneeId   = assigneeIds.get(i % parallelism);
                String assigneeName = assigneeNames.getOrDefault(assigneeId, "");

                InterviewSlot slot = InterviewSlot.builder()
                        .interviewId(interviewId)
                        .candidateId(candidateId)
                        .candidateName(candidate.getFirstName() + " " + candidate.getLastName())
                        .candidateEmail(candidate.getEmail())
                        .assigneeId(assigneeId)
                        .assigneeName(assigneeName)
                        .slotStart(roundStart)
                        .slotEnd(roundEnd)
                        .status(InterviewSlot.SlotStatus.SCHEDULED)
                        .decision(DECISION_PENDING)
                        .build();

                slots.add(slotRepository.save(slot));
            }
        }

        // Compute phase end = last slot end
        LocalDateTime phaseEnd = slots.stream()
                .map(InterviewSlot::getSlotEnd)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        interview.setSlotsGenerated(true);
        interview.setPhaseStatus("IN_PROGRESS");
        interview.setComputedEndDateTime(phaseEnd);
        interviewRepository.save(interview);

        log.info("Generated {} slots for interview {} (parallelism={}, phases={})",
                slots.size(), interviewId, parallelism, interview.getStageType());
        return slots.stream().map(this::toSlotResponse).toList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULE SUGGESTION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Auto-calculates a suggested schedule for an interview phase,
     * given the number of candidates and assignees.
     */
    @Transactional(readOnly = true)
    public ScheduleSuggestionResponse suggestSchedule(String interviewId, LocalDate desiredStart) {
        Interview interview = findInterview(interviewId);

        int candidateCount = getEligibleCandidates(interview).size();
        List<String> assigneeIds = parseJsonList(interview.getAssigneeIdsJson());
        if (assigneeIds.isEmpty() && interview.getAssigneeId() != null) {
            assigneeIds = List.of(interview.getAssigneeId());
        }
        int assigneeCount = assigneeIds.isEmpty() ? 1 : assigneeIds.size();

        int duration    = interview.getDurationMinutes() != null ? interview.getDurationMinutes() : 60;
        int maxPerDay   = interview.getInterviewsPerDay() != null ? interview.getInterviewsPerDay() : 4;
        String dayStart = interview.getDayStartTime() != null ? interview.getDayStartTime() : TIME_START;
        String dayEnd   = interview.getDayEndTime()   != null ? interview.getDayEndTime()   : TIME_END;
        List<String> excludedRanges = parseJsonList(interview.getExcludedHoursJson());

        // Calculate working minutes per day minus excluded
        int workingMinutes = computeWorkingMinutesPerDay(dayStart, dayEnd, excludedRanges);
        int roundsPerDay   = Math.min(workingMinutes / duration, maxPerDay);
        if (roundsPerDay <= 0) roundsPerDay = 1;

        // Total rounds = ceil(candidates / parallelism)
        int totalRounds = (int) Math.ceil((double) candidateCount / assigneeCount);

        // Days needed = ceil(rounds / rounds_per_day), skipping weekends
        int daysNeeded = (int) Math.ceil((double) totalRounds / roundsPerDay);

        // Adjust for weekends
        int actualCalendarDays = countCalendarDaysNeeded(daysNeeded);

        // Blocked dates from previous phase
        Set<LocalDate> blockedDates = getBlockedDatesForPhase(interview);

        // Find the actual start date (respecting phase constraints)
        LocalDate startDate = ajusterDateDebutPhase(interview, desiredStart);

        LocalDate suggestedEnd = computeEndDate(startDate, daysNeeded, blockedDates);

        return ScheduleSuggestionResponse.builder()
                .candidateCount(candidateCount)
                .assigneeCount(assigneeCount)
                .durationMinutes(duration)
                .roundsPerDay(roundsPerDay)
                .totalRoundsNeeded(totalRounds)
                .estimatedDaysNeeded(actualCalendarDays)
                .suggestedStartDate(startDate)
                .suggestedEndDate(suggestedEnd)
                .blockedDates(new ArrayList<>(blockedDates))
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CLOSE PHASE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Closes a phase manually. This allows the next phase to be configured.
     * - RH closure enables Technical configuration
     * - Technical closure enables Admin configuration
     */
    @Transactional
    public InterviewSummaryResponse closePhase(String interviewId, ClosePhaseRequest req) {
        Interview interview = findInterview(interviewId);

        if (STATUS_CLOSED.equals(interview.getPhaseStatus())) {
            throw new IllegalStateException("Phase already closed");
        }

        // For Admin: both RH and Technical must be closed first
        if (interview.getStageType() == StageType.ADMIN_INTERVIEW) {
            ensurePreviousPhasesClosedForAdmin(interview.getJobId());
        }

        interview.setPhaseStatus(STATUS_CLOSED);
        interviewRepository.save(interview);

        log.info("Phase CLOSED: interview={}, type={}, reason={}",
                interviewId, interview.getStageType(), req != null ? req.getReason() : "");
        return toSummary(interview);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SUBMIT EVALUATION
    // ══════════════════════════════════════════════════════════════════════════

    @Transactional
    public SlotResponse submitEvaluation(String slotId, EvaluationSubmitRequest req) {
        InterviewSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new IllegalArgumentException("Slot not found: " + slotId));

        try {
            slot.setEvaluationResultJson(objectMapper.writeValueAsString(req.getScores()));
        } catch (Exception e) {
            log.warn("Could not serialize evaluation: {}", e.getMessage());
        }

        slot.setComment(req.getComment());
        slot.setOverallScore(req.getOverallScore());
        slot.setDecision(req.getDecision());
        slot.setStatus(InterviewSlot.SlotStatus.COMPLETED);

        String jobId = findInterview(slot.getInterviewId()).getJobId();

        if (DECISION_ACCEPTED.equals(req.getDecision())) {
            advanceStageProgress(slot.getCandidateId(), jobId);
        } else if (DECISION_REJECTED.equals(req.getDecision())) {
            rejectStageProgress(slot.getCandidateId(), jobId);
        }

        // Auto-close phase if all scheduled slots are now evaluated
        autoClosePhaseIfComplete(slot.getInterviewId());

        return toSlotResponse(slotRepository.save(slot));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANDIDATE VIEW — slots without evaluation details
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns all interview slots assigned to a candidate,
     * enriched with job/stage context but WITHOUT evaluation grid or scores.
     */
    @Transactional(readOnly = true)
    public List<CandidateSlotView> getCandidateSlots(String candidateId) {
        return slotRepository.findByCandidateId(candidateId).stream()
                .sorted(Comparator.comparing(
                        s -> s.getSlotStart() != null ? s.getSlotStart() : LocalDateTime.MIN))
                .map(slot -> {
                    Interview interview = interviewRepository.findById(slot.getInterviewId()).orElse(null);
                    return CandidateSlotView.builder()
                            .id(slot.getId())
                            .jobId(interview != null ? interview.getJobId() : null)
                            .jobTitle(interview != null ? interview.getJobTitle() : null)
                            .stageName(interview != null ? interview.getStageName() : null)
                            .stageType(interview != null ? interview.getStageType() : null)
                            .slotStart(slot.getSlotStart() != null ? slot.getSlotStart().toString() : null)
                            .slotEnd(slot.getSlotEnd() != null ? slot.getSlotEnd().toString() : null)
                            .status(slot.getStatus() != null ? slot.getStatus().name() : null)
                            .decision(slot.getDecision())
                            .assigneeName(slot.getAssigneeName())
                            .build();
                })
                .toList();
    }

    @Transactional
    public void deleteInterviewsForJob(Job job) {
        interviewRepository.deleteAllByJobId(job.getId());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PHASE VALIDATION LOGIC
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Before configuring an interview, validates that the previous phase is CLOSED.
     * RH: no prerequisite
     * Technical: RH must be CLOSED
     * Admin: both RH and Technical must be CLOSED
     */
    private void validatePhasePrerequisites(Interview interview) {
        String jobId = interview.getJobId();
        StageType type = interview.getStageType();

        if (type == StageType.TECHNICAL_INTERVIEW) {
            List<Interview> rhList = interviewRepository.findByJobIdAndStageType(jobId, StageType.RH_INTERVIEW);
            if (!rhList.isEmpty() && !STATUS_CLOSED.equals(rhList.get(0).getPhaseStatus())) {
                throw new IllegalStateException(
                        "Cannot configure Technical interview: RH interview phase is not CLOSED yet. " +
                        "Current status: " + rhList.get(0).getPhaseStatus());
            }
        }

        if (type == StageType.ADMIN_INTERVIEW) {
            ensurePreviousPhasesClosedForAdmin(jobId);
        }
    }

    /**
     * Checks that all preceding TEST stages (RH_TEST, TECHNICAL_TEST) in the workflow
     * have no candidates still PENDING or IN_PROGRESS.
     * This ensures the test period is finished before scheduling interviews.
     */
    private void validatePrecedingTestsCompleted(Interview interview) {
        List<WorkflowStage> allStages = workflowStageRepository.findByJobId(interview.getJobId())
                .stream()
                .filter(s -> s.getStageOrder() != null && s.getStageType() != null)
                .sorted(Comparator.comparing(WorkflowStage::getStageOrder))
                .toList();

        WorkflowStage thisStage = allStages.stream()
                .filter(s -> s.getId().equals(interview.getWorkflowStageId()))
                .findFirst().orElse(null);
        if (thisStage == null) return;

        List<StageType> testTypes = List.of(StageType.RH_TEST, StageType.TECHNICAL_TEST);

        for (WorkflowStage stage : allStages) {
            if (stage.getStageOrder() >= thisStage.getStageOrder()) {
                break;
            }
            if (!testTypes.contains(stage.getStageType())) {
                continue;
            }

            long activeCount = stageProgressRepository
                    .findByJobIdAndStageType(interview.getJobId(), stage.getStageType().name())
                    .stream()
                    .filter(p -> p.getStatus() == StageProgressStatus.IN_PROGRESS
                              || p.getStatus() == StageProgressStatus.PENDING)
                    .count();

            if (activeCount > 0) {
                throw new IllegalStateException(
                        "Cannot generate interview slots: the test phase '" + stage.getName() +
                        "' still has " + activeCount + " candidate(s) in progress or pending. " +
                        "All tests must be completed or rejected before scheduling interviews.");
            }
        }
    }

    private void ensurePreviousPhasesClosedForAdmin(String jobId) {
        List<Interview> rhList   = interviewRepository.findByJobIdAndStageType(jobId, StageType.RH_INTERVIEW);
        List<Interview> techList = interviewRepository.findByJobIdAndStageType(jobId, StageType.TECHNICAL_INTERVIEW);

        boolean rhOk   = rhList.isEmpty()   || STATUS_CLOSED.equals(rhList.get(0).getPhaseStatus());
        boolean techOk = techList.isEmpty() || STATUS_CLOSED.equals(techList.get(0).getPhaseStatus());

        if (!rhOk) {
            throw new IllegalStateException("Cannot proceed to Admin interview: RH phase is not CLOSED");
        }
        if (!techOk) {
            throw new IllegalStateException("Cannot proceed to Admin interview: Technical phase is not CLOSED");
        }
    }

    /**
     * Additional prerequisites specifically before generating slots.
     * For Technical: validates that startDate does not fall on any RH-occupied date.
     * For Admin: validates that startDate does not fall on any RH or Technical-occupied date.
     * Also validates that all preceding TEST stages are completed.
     */
    private void validateGenerateSlotsPrerequisites(Interview interview) {
        // Configuration must exist
        validatePhasePrerequisites(interview);

        // Preceding tests must be done
        validatePrecedingTestsCompleted(interview);

        String jobId = interview.getJobId();
        LocalDate startDate = interview.getStartDate();

        if (interview.getStageType() == StageType.TECHNICAL_INTERVIEW) {
            validateTechnicalStartDate(jobId, startDate);
        }

        if (interview.getStageType() == StageType.ADMIN_INTERVIEW) {
            validateAdminStartDate(jobId, startDate);
        }
    }

    private LocalDate ajusterDateDebutPhase(Interview interview, LocalDate desiredStart) {
        LocalDate startDate = desiredStart != null ? desiredStart : LocalDate.now();
        if (interview.getStageType() == StageType.TECHNICAL_INTERVIEW) {
            List<Interview> rhList = interviewRepository.findByJobIdAndStageType(
                    interview.getJobId(), StageType.RH_INTERVIEW);
            if (!rhList.isEmpty() && rhList.get(0).getComputedEndDateTime() != null) {
                LocalDate minStart = rhList.get(0).getComputedEndDateTime().toLocalDate().plusDays(1);
                if (startDate.isBefore(minStart)) startDate = minStart;
            }
        }
        if (interview.getStageType() == StageType.ADMIN_INTERVIEW) {
            List<Interview> techList = interviewRepository.findByJobIdAndStageType(
                    interview.getJobId(), StageType.TECHNICAL_INTERVIEW);
            if (!techList.isEmpty() && techList.get(0).getComputedEndDateTime() != null) {
                LocalDate minStart = techList.get(0).getComputedEndDateTime().toLocalDate().plusDays(1);
                if (startDate.isBefore(minStart)) startDate = minStart;
            }
        }
        return startDate;
    }

    private void validateTechnicalStartDate(String jobId, LocalDate startDate) {
        List<Interview> rhList = interviewRepository.findByJobIdAndStageType(jobId, StageType.RH_INTERVIEW);
        if (rhList.isEmpty()) return;
        Interview rh = rhList.get(0);
        if (rh.getComputedEndDateTime() != null) {
            LocalDate rhEndDate = rh.getComputedEndDateTime().toLocalDate();
            if (!startDate.isAfter(rhEndDate)) {
                throw new IllegalStateException(
                        "Technical interview cannot start on " + startDate +
                        " — RH phase ends on " + rhEndDate + ". Choose a start date after " + rhEndDate);
            }
        }
        List<LocalDate> rhDates = slotRepository.findOccupiedDatesByInterviewIds(List.of(rh.getId()));
        if (startDate != null && rhDates.contains(startDate)) {
            throw new IllegalStateException(
                    "Date " + startDate + " is already used by the RH interview phase");
        }
    }

    private void validateAdminStartDate(String jobId, LocalDate startDate) {
        List<Interview> rhList   = interviewRepository.findByJobIdAndStageType(jobId, StageType.RH_INTERVIEW);
        List<Interview> techList = interviewRepository.findByJobIdAndStageType(jobId, StageType.TECHNICAL_INTERVIEW);
        if (!techList.isEmpty() && techList.get(0).getComputedEndDateTime() != null) {
            LocalDate techEnd = techList.get(0).getComputedEndDateTime().toLocalDate();
            if (!startDate.isAfter(techEnd)) {
                throw new IllegalStateException(
                        "Admin interview cannot start on " + startDate +
                        " — Technical phase ends on " + techEnd);
            }
        }
        List<String> previousIds = new ArrayList<>();
        rhList.forEach(i -> previousIds.add(i.getId()));
        techList.forEach(i -> previousIds.add(i.getId()));
        if (!previousIds.isEmpty()) {
            List<LocalDate> occupied = slotRepository.findOccupiedDatesByInterviewIds(previousIds);
            if (occupied.contains(startDate)) {
                throw new IllegalStateException(
                        "Date " + startDate + " is already used by a previous interview phase");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SLOT GENERATION ALGORITHM
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Generates ordered LocalDateTime "rounds".
     * Each round corresponds to one time slot during which all assignees can interview simultaneously.
     * Blocked dates (from previous phases) are skipped entirely.
     */
    private record SlotConfig(int duration, int maxRoundsPerDay,
                              String dayStart, String dayEnd, List<String> excludedRanges) {}

    private List<LocalDateTime> generateRounds(
            LocalDate start, LocalDate end,
            SlotConfig cfg, Set<LocalDate> blockedDates) {
        int durationMinutes = cfg.duration();
        int maxRoundsPerDay = cfg.maxRoundsPerDay();
        List<LocalDateTime> rounds   = new ArrayList<>();
        List<LocalTime[]>   excluded = parseExcludedRanges(cfg.excludedRanges());
        LocalTime dayStart = parseTime(cfg.dayStart(), LocalTime.of(9, 0));
        LocalTime dayEnd   = parseTime(cfg.dayEnd(),   LocalTime.of(18, 0));

        LocalDate current = start;
        while (!current.isAfter(end)) {
            DayOfWeek dow = current.getDayOfWeek();

            // Skip weekends
            boolean isWeekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            // Skip dates occupied by a previous phase
            boolean isBlocked = blockedDates.contains(current);

            if (!isWeekend && !isBlocked) {
                LocalTime time         = dayStart;
                int       roundsToday  = 0;

                while (roundsToday < maxRoundsPerDay) {
                    LocalTime slotEnd = time.plusMinutes(durationMinutes);
                    if (slotEnd.isAfter(dayEnd)) break;

                    if (!overlapsExcluded(time, slotEnd, excluded)) {
                        rounds.add(LocalDateTime.of(current, time));
                        roundsToday++;
                    }
                    time = slotEnd;
                }
            }
            current = current.plusDays(1);
        }
        return rounds;
    }

    private boolean overlapsExcluded(LocalTime slotStart, LocalTime slotEnd, List<LocalTime[]> excluded) {
        for (LocalTime[] range : excluded) {
            if (slotStart.isBefore(range[1]) && slotEnd.isAfter(range[0])) return true;
        }
        return false;
    }

    private List<LocalTime[]> parseExcludedRanges(List<String> ranges) {
        List<LocalTime[]> result = new ArrayList<>();
        for (String r : ranges) {
            if (r == null || !r.contains("-")) {
                continue;
            }
            String[] parts = r.split("-", 2);
            if (parts.length != 2) {
                continue;
            }
            try {
                LocalTime from = LocalTime.parse(parts[0].trim());
                LocalTime to   = LocalTime.parse(parts[1].trim());
                if (from.isBefore(to)) result.add(new LocalTime[]{from, to});
            } catch (Exception e) {
                log.warn("Cannot parse excluded range '{}': {}", r, e.getMessage());
            }
        }
        return result;
    }

    private LocalTime parseTime(String hhmm, LocalTime fallback) {
        if (hhmm == null || hhmm.isBlank()) return fallback;
        try { return LocalTime.parse(hhmm); } catch (Exception e) { return fallback; }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ELIGIBLE CANDIDATES
    // ══════════════════════════════════════════════════════════════════════════

    private List<String> getEligibleCandidates(Interview interview) {
        List<ApplicationStageProgress> rows =
                stageProgressRepository.findByJobIdAndStageType(
                        interview.getJobId(), interview.getStageType().name());

        return rows.stream()
                .filter(r -> r.getStatus() == StageProgressStatus.IN_PROGRESS)
                .map(ApplicationStageProgress::getCandidateId)
                .distinct()
                .toList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BLOCKED DATES HELPER
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the set of dates that are blocked for a given phase
     * (i.e., dates already occupied by a preceding phase's slots).
     */
    private Set<LocalDate> getBlockedDatesForPhase(Interview interview) {
        String jobId = interview.getJobId();
        List<String> previousIds = new ArrayList<>();

        if (interview.getStageType() == StageType.TECHNICAL_INTERVIEW) {
            interviewRepository.findByJobIdAndStageType(jobId, StageType.RH_INTERVIEW)
                    .forEach(i -> previousIds.add(i.getId()));
        } else if (interview.getStageType() == StageType.ADMIN_INTERVIEW) {
            interviewRepository.findByJobIdAndStageType(jobId, StageType.RH_INTERVIEW)
                    .forEach(i -> previousIds.add(i.getId()));
            interviewRepository.findByJobIdAndStageType(jobId, StageType.TECHNICAL_INTERVIEW)
                    .forEach(i -> previousIds.add(i.getId()));
        }

        if (previousIds.isEmpty()) return Collections.emptySet();
        return new HashSet<>(slotRepository.findOccupiedDatesByInterviewIds(previousIds));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // AUTO-CLOSE PHASE
    // ══════════════════════════════════════════════════════════════════════════

    private void autoClosePhaseIfComplete(String interviewId) {
        long remaining = slotRepository.countScheduledByInterviewId(interviewId);
        if (remaining == 0) {
            interviewRepository.findById(interviewId).ifPresent(i -> {
                if (!STATUS_CLOSED.equals(i.getPhaseStatus())) {
                    i.setPhaseStatus(STATUS_CLOSED);
                    interviewRepository.save(i);
                    log.info("Auto-closed phase for interview {}", interviewId);
                }
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STAGE PROGRESS
    // ══════════════════════════════════════════════════════════════════════════

    private void advanceStageProgress(String candidateId, String jobId) {
        List<ApplicationStageProgress> stages = stageProgressRepository
                .findByCandidateIdAndJobId(candidateId, jobId).stream()
                .sorted(Comparator.comparing(ApplicationStageProgress::getStageOrder))
                .toList();

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

    // ══════════════════════════════════════════════════════════════════════════
    // SCHEDULE CALCULATION HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private int computeWorkingMinutesPerDay(String dayStart, String dayEnd, List<String> excludedRanges) {
        LocalTime start = parseTime(dayStart, LocalTime.of(9, 0));
        LocalTime end   = parseTime(dayEnd,   LocalTime.of(18, 0));
        int total = (int) Duration.between(start, end).toMinutes();

        List<LocalTime[]> excluded = parseExcludedRanges(excludedRanges);
        for (LocalTime[] range : excluded) {
            LocalTime from = range[0].isBefore(start) ? start : range[0];
            LocalTime to   = range[1].isAfter(end)    ? end   : range[1];
            if (from.isBefore(to)) {
                total -= (int) Duration.between(from, to).toMinutes();
            }
        }
        return Math.max(total, 0);
    }

    /** Counts calendar days needed for `workingDays` working days (skipping weekends). */
    private int countCalendarDaysNeeded(int workingDays) {
        int weeks    = workingDays / 5;
        int extra    = workingDays % 5;
        return weeks * 7 + extra;
    }

    /** Returns the end date after `workingDaysNeeded` working days, skipping blocked dates. */
    private LocalDate computeEndDate(LocalDate start, int workingDaysNeeded, Set<LocalDate> blockedDates) {
        LocalDate current = start;
        int used = 0;
        while (used < workingDaysNeeded) {
            if (current.getDayOfWeek() != DayOfWeek.SATURDAY
                    && current.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !blockedDates.contains(current)) {
                used++;
            }
            if (used < workingDaysNeeded) current = current.plusDays(1);
        }
        return current;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MAPPERS
    // ══════════════════════════════════════════════════════════════════════════

    private InterviewSummaryResponse toSummary(Interview i) {
        List<String>       excluded    = parseJsonList(i.getExcludedHoursJson());
        List<String>       assigneeIds = parseJsonList(i.getAssigneeIdsJson());
        List<AssigneeDTO>  assignees   = resolveAssignees(assigneeIds);

        int total     = slotRepository.countByInterviewId(i.getId());
        int completed = slotRepository.countByInterviewIdAndStatus(i.getId(), InterviewSlot.SlotStatus.COMPLETED);

        int eligible = 0;
        try {
            eligible = getEligibleCandidates(i).size();
        } catch (Exception ignored) { // intentionally empty
        }

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
                .dayStartTime(i.getDayStartTime() != null ? i.getDayStartTime() : TIME_START)
                .dayEndTime(i.getDayEndTime()     != null ? i.getDayEndTime()   : TIME_END)
                .durationMinutes(i.getDurationMinutes())
                .interviewsPerDay(i.getInterviewsPerDay())
                .gridConfigured(i.getGridConfigured())
                .scheduleConfigured(i.getScheduleConfigured())
                .slotsGenerated(i.getSlotsGenerated())
                .totalCandidates(total)
                .completedInterviews(completed)
                .excludedHours(excluded)
                .assignees(assignees)
                .phaseStatus(i.getPhaseStatus() != null ? i.getPhaseStatus() : STATUS_NOT_CONFIGURED)
                .computedEndDateTime(i.getComputedEndDateTime())
                .eligibleCandidates(eligible)
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
                .toList();
    }

    private Map<String, String> loadAssigneeNames(List<String> ids) {
        Map<String, String> names = new HashMap<>();
        for (String id : ids) {
            userRepository.findById(id).ifPresent(u ->
                    names.put(id, u.getFirstName() + " " + u.getLastName()));
        }
        return names;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JSON HELPERS
    // ══════════════════════════════════════════════════════════════════════════

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
                .orElseThrow(() -> new IllegalArgumentException("Interview not found: " + id));
    }
}
