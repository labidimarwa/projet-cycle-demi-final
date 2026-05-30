package com.nexgenai.dto.notification;

import com.nexgenai.model.enums.NotificationType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class NotificationDto {
    private String           id;
    private NotificationType type;
    private String           title;
    private String           message;
    private String           relatedEntityId;
    private String           relatedEntityType;
    private String           link;
    private boolean          read;
    private Instant          createdAt;
}
