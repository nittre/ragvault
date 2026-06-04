package com.ragservice.rag.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "file_processing")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FileProcessing {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid default gen_random_uuid()")
    private UUID id;

    @Column(nullable = false)
    private String userEmail;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(nullable = false)
    private String originalName;

    private String mimeType;

    @Column(nullable = false)
    private Long sizeBytes;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String extractedText;

    private Integer tokenCount;
    private String tokenizerModel;
    private Integer imageCount;
    private Integer ocrImageCount;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
