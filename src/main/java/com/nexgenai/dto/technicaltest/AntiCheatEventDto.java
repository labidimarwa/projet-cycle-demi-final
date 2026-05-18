
 
package com.nexgenai.dto.technicaltest;
 
import lombok.*;
import java.util.List;
import java.util.Map;
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AntiCheatEventDto {
    private String sessionId;      // ✅ AJOUTÉ — propagé depuis le frontend
    private String type;           // TAB_SWITCH | WINDOW_BLUR | PASTE | COPY | DEVTOOLS | RIGHT_CLICK
    private String detail;
    private int    questionIndex;
    private String timestamp;      // ISO-8601 UTC string
}
 