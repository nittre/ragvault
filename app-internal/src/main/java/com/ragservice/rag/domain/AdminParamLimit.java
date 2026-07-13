package com.ragservice.rag.domain;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "admin_param_limits")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminParamLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "param_name", unique = true, nullable = false)
    private String paramName;

    private BigDecimal minValue;

    private BigDecimal maxValue;

    /** NULL = Guard A (범위 클램핑), 값 있으면 Guard B (강제 고정). ADR-0005 */
    private BigDecimal fixedValue;

    /** "A" = 클램핑, "B" = 강제 고정 */
    @Column(name = "guard_type", nullable = false)
    private String guardType;

    private String description;

    /** Guard B 잠금 사유 — UI 표시용. guard_type='B' 일 때만 의미 있음. */
    @Column(name = "locked_reason")
    private String lockedReason;

    /** Stage 1 기본값(문자열로 저장 — 숫자/enum 문자열 모두 수용). 서버 코드 하드코딩 폴백 없음(ADR-0005). */
    @Column(name = "default_value")
    private String defaultValue;

    private String updatedBy;

    private LocalDateTime updatedAt;

    /**
     * Guard B(강제 고정) 여부 편의 메서드.
     * is_locked 컬럼 없이 guard_type='B' 로 판별 (ADR-0005).
     */
    public boolean isLocked() {
        return "B".equals(this.guardType);
    }
}
