package com.nexgenai.model;
 
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalTime;
 
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "interview_excluded_slots")
@ToString(exclude = "interview")
public class InterviewExcludedSlot {
 
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
 
    /** If set: exclude specific day */
    @Column(name = "excluded_date")
    private LocalDate excludedDate;
 
    /** If set: exclude recurring weekday (1=Monday … 7=Sunday) */
    @Column(name = "day_of_week")
    private Integer dayOfWeek;
 
    /** Optional: only exclude a time range within the day */
    @Column(name = "slot_start")
    private LocalTime slotStart;
 
    @Column(name = "slot_end")
    private LocalTime slotEnd;
 
    /** Human-readable reason */
    @Column(name = "reason", length = 255)
    private String reason;
 
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interview_id")
    @JsonIgnore
    private Interview interview;
}
 