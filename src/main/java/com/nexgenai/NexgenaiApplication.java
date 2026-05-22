package com.nexgenai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@EnableAsync
@SpringBootApplication
public class NexgenaiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexgenaiApplication.class, args);
        System.out.println("=========================================");
        System.out.println("   NexGen AI Backend - Inheritance Mode");
        System.out.println("   http://localhost:8080/api/v1");
        System.out.println("=========================================");
    }

    @Bean("matchingExecutor")
    public Executor matchingExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(5);
        exec.setMaxPoolSize(10);
        exec.setQueueCapacity(25);
        exec.setThreadNamePrefix("matching-");
        exec.initialize();
        return exec;
    }
    
    
  
}