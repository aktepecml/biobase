package com.example.fingerprint.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FingerprintProperties.class)
public class AppConfig {
}
