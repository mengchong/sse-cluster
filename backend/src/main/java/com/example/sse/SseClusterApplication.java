package com.example.sse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableBinding
@EnableScheduling
public class SseClusterApplication {

    public static void main(String[] args) {
        SpringApplication.run(SseClusterApplication.class, args);
    }
}
