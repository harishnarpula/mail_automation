package com.askoxy.emailautomation.controller;

import com.askoxy.emailautomation.dto.ApiResponse;
import com.askoxy.emailautomation.dto.ChatDto;
import com.askoxy.emailautomation.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Radha Clone — public AI chatbot.
 * POST /api/v1/radha/chat
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/radha")
public class RadhaController {

    private final ChatService chatService;

    @PostMapping("/chat")
    public ApiResponse<ChatDto> chat(@RequestBody ChatDto request) {
        return ApiResponse.success(chatService.chat(request));
    }

    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.success("Radha AI is online");
    }
}
