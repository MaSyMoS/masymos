package org.masymos.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "org.masymos")
public class MaSyMoSApplication {
    public static void main(String[] args) {
        SpringApplication.run(MaSyMoSApplication.class, args);
    }
}
