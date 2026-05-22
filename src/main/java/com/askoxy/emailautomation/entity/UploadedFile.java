package com.askoxy.emailautomation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "uploaded_doc")
public class UploadedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String fileId;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String fileType;

    @Column(unique = true)
    private String vectorStoreId;

    @Column(nullable = false)
    private String uploadStatus;

    @Column(nullable = false)
    private Integer totalChunks = 0;

    @CreationTimestamp
    private LocalDateTime createdAt;
}