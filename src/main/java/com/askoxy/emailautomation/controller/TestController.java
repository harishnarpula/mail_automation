package com.askoxy.emailautomation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.net.Socket;

@RestController
public class TestController {

    @GetMapping("/test-smtp")
    public String testSmtp() {
        try {
            Socket socket = new Socket();

            socket.connect(
                    new InetSocketAddress(
                            "smtp.gmail.com",
                            587
                    ),
                    10000
            );

            return "CONNECTED";

        } catch (Exception e) {
            return e.toString();
        }
    }
}