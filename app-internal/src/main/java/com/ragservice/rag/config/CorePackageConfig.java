package com.ragservice.rag.config;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Configuration;

// com.ragvault.core 의 JPA 엔티티·리포지토리를 JpaRepositoriesAutoConfiguration 스캔 대상에 추가.
// @EnableJpaRepositories 를 직접 선언하면 @WebMvcTest 슬라이스에서 entityManagerFactory 없이
// 리포지토리 빈 생성을 시도해 실패하므로, AutoConfigurationPackages 를 확장하는 방식을 사용.
@Configuration
@AutoConfigurationPackage(basePackages = {"com.ragvault.core"})
public class CorePackageConfig {
}
