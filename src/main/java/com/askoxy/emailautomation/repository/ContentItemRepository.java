package com.askoxy.emailautomation.repository;

import com.askoxy.emailautomation.entity.ContentItem;
import com.askoxy.emailautomation.enums.ContentStatus;
import com.askoxy.emailautomation.enums.PlatformType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContentItemRepository extends JpaRepository<ContentItem, Long> {
    Optional<ContentItem> findByContentId(String contentId);
    List<ContentItem> findByStatus(ContentStatus status);
    List<ContentItem> findByPlatform(PlatformType platform);
}
