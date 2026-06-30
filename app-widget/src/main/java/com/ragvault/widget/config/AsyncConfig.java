package com.ragvault.widget.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 비동기 처리 활성화 설정.
 *
 * proxyTargetClass = true 필수 (LL-0005):
 * - 기본값 false 시 JDK 동적 프록시 적용 → 구체 타입(@Component/@Service) 주입 불일치
 * - CGLIB 프록시는 구체 클래스를 상속하므로 instanceof 통과
 *
 * @SpringBootApplication 이 아닌 별도 @Configuration 클래스로 분리.
 */
@Configuration
@EnableAsync(proxyTargetClass = true)
public class AsyncConfig {
}
