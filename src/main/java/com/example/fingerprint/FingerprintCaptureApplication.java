package com.example.fingerprint;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FingerprintCaptureApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(FingerprintCaptureApplication.class);
        application.setHeadless(false);
        application.run(args);
    }
}
