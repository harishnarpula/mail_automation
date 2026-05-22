// com/askoxy/emailautomation/approval/PendingApprovalState.java
package com.askoxy.emailautomation.entity;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory store of campaigns currently awaiting admin approval.
 * Key: admin phone number (normalized, no @c.us)
 * Value: ApprovalContext — everything needed to send the email if approved
 */
@Component
public class PendingApprovalState {

    private final Map<String, ApprovalContext> pendingMap = new ConcurrentHashMap<>();

    public void set(String adminNumber, ApprovalContext context) {
        pendingMap.put(normalize(adminNumber), context);
    }

    public Optional<ApprovalContext> get(String adminNumber) {
        return Optional.ofNullable(pendingMap.get(normalize(adminNumber)));
    }

    public void clear(String adminNumber) {
        pendingMap.remove(normalize(adminNumber));
    }

    public boolean hasPending(String adminNumber) {
        return pendingMap.containsKey(normalize(adminNumber));
    }

    private String normalize(String number) {
        // Strip @c.us suffix if present
        return number != null ? number.replace("@c.us", "").trim() : "";
    }
}