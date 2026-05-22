package com.askoxy.emailautomation.repository;

import com.askoxy.emailautomation.entity.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UploadedFileRepository extends JpaRepository<UploadedFile, Long> {
    Optional<UploadedFile> findByFileId(String fileId);

    Optional<UploadedFile> findTopByUploadStatusOrderByCreatedAtDesc(String uploadStatus);
}