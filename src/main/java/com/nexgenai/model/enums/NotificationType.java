package com.nexgenai.model.enums;

public enum NotificationType {

    // ── Candidate ──────────────────────────────────────────────────────────────
    APPLICATION_ACCEPTED,     // HR accepted this application
    APPLICATION_REJECTED,     // HR rejected this application
    STAGE_COMPLETED,          // Candidate advanced to the next stage
    TEST_ASSIGNED,            // A test or assessment stage became active
    INTERVIEW_SCHEDULED,      // An interview slot was confirmed
    MATCH_COMPUTED,           // AI match score is ready to view
    JOB_CLOSED,               // A job the candidate applied to was closed

    // ── HR / Admin ────────────────────────────────────────────────────────────
    NEW_APPLICATION,          // A new candidate applied to one of HR's jobs
    MATCH_READY,              // AI match score computed for a candidate
    TEST_SUBMITTED,           // A candidate submitted a test session
    JOB_CREATED,              // HR published a new job (admin receives this)
    STAGE_UPDATED,            // A workflow stage was manually updated

    // ── Evaluator ─────────────────────────────────────────────────────────────
    INTERVIEW_ASSIGNED,       // An interview was assigned to this evaluator
    TEST_SESSION_TO_GRADE,    // A test session is awaiting grading
}
