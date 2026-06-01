package com.nexgenai.dto.anticheat;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class RecordAntiCheatEventRequest {
    private String testId;
    private String sessionId;
    private String type;
    private String detail;
    private int questionIndex;
    private LocalDateTime occurredAt;
}
