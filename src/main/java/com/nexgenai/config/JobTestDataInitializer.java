package com.nexgenai.config;

import com.nexgenai.service.AssessmentCrudService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!test")
@Component
@RequiredArgsConstructor
public class JobTestDataInitializer implements CommandLineRunner {

    private final AssessmentCrudService assessmentCrudService;

    @Override
    public void run(String... args) {
        assessmentCrudService.initBuiltInModels();
    }
}
