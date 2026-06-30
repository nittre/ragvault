package com.ragservice.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.ragservice.rag", "com.ragvault.core"})
@EntityScan(basePackages = {"com.ragservice", "com.ragvault.core"})
@EnableScheduling
@EnableAsync(proxyTargetClass = true)  // CGLIB proxy → @Async 빈이 구체 타입 유지 (JDK proxy 시 BeanNotOfRequiredTypeException)
@EnableRetry
public class RagBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagBackendApplication.class, args);
    }
}
