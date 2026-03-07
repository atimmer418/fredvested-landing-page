package com.fredvested.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.actuate.autoconfigure.metrics.SystemMetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.web.tomcat.TomcatMetricsAutoConfiguration;

@SpringBootApplication(exclude = {
    SystemMetricsAutoConfiguration.class,
    TomcatMetricsAutoConfiguration.class
})
public class WaitlistApplication {
    public static void main(String[] args) {
        // Disable JMX and container detection to prevent cgroup access
        System.setProperty("com.sun.management.jmxremote", "false");
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(WaitlistApplication.class, args);
    }
}  