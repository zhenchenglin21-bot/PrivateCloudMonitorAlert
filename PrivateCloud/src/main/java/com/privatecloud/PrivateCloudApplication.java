package com.privatecloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PrivateCloudApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrivateCloudApplication.class, args);
    }
}
