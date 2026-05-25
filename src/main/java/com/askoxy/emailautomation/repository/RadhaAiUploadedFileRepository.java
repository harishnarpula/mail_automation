package com.askoxy.emailautomation.repository;

import com.askoxy.emailautomation.entity.RadhaAiUploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RadhaAiUploadedFileRepository extends JpaRepository<RadhaAiUploadedFile, Long> {
    Optional<RadhaAiUploadedFile> findByFileId(String fileId);
}
