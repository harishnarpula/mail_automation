package com.askoxy.emailautomation.entity;

import com.askoxy.emailautomation.enums.UploadStatus;
import jakarta.persistence.*;
import lombok.*;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@Entity @Table(name = "uploaded_files")
public class RadhaAiUploadedFile extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String fileId;

    @Column(nullable = false)
    private String fileName;

    /** Stores KnowledgeType name (COMPANY_KNOWLEDGE) */
    @Column(nullable = false)
    private String knowledgeType;

    /** Stores PlatformType name — used for Qdrant metadata filter */
    @Column(nullable = false)
    private String clientName;

    @Column
    private String vectorStoreId;

    @Column(nullable = false)
    private String fileType;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private UploadStatus uploadStatus;

    @Column(nullable = false)
    private Integer totalChunks = 0;
    @Column
    private String campaignId;
}
