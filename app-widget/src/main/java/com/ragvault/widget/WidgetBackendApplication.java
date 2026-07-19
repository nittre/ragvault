package com.ragvault.widget;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.ragvault.widget", "com.ragvault.core"})
@EntityScan(basePackages = {"com.ragvault"})
@EnableScheduling
public class WidgetBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(WidgetBackendApplication.class, args);
    }
}
