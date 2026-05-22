package com.askoxy.emailautomation.agent;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentResult<T> {
    private boolean success;
    private T data;
    private String errorMessage;
}