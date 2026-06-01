package com.nexgenai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Slf4j
public class NexgenaiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexgenaiApplication.class, args);
        log.info("=========================================");
        log.info("   NexGen AI Backend - Inheritance Mode");
        log.info("   http://localhost:8080/api/v1");
        log.info("=========================================");
    }
}