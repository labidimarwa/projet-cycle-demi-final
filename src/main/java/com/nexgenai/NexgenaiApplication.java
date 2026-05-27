package com.nexgenai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NexgenaiApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexgenaiApplication.class, args);
        System.out.println("=========================================");
        System.out.println("   NexGen AI Backend - Inheritance Mode");
        System.out.println("   http://localhost:8080/api/v1");
        System.out.println("=========================================");
    }
}