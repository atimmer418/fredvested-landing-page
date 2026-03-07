package com.fredvested.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.actuate.autoconfigure.metrics.SystemMetricsAutoConfiguration;

@SpringBootApplication(exclude = {SystemMetricsAutoConfiguration.class})
public class WaitlistApplication {
    public static void main(String[] args) {
        SpringApplication.run(WaitlistApplication.class, args);
    }
}  