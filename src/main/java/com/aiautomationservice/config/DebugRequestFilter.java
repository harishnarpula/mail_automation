package com.aiautomationservice.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DEBUG FILTER — logs every single incoming HTTP request raw.
 * This will tell us EXACTLY what UltraMsg sends and why Spring ignores it.
 * Remove this after debugging is done.
 */
@Component
@Order(1)
public class DebugRequestFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(DebugRequestFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        // Skip filter for file uploads — multipart/form-data breaks body caching
        if (req.getContentType() != null && req.getContentType().contains("multipart/form-data")) {
            chain.doFilter(request, response);
            return;
        }

        // Cache the body so it can be read multiple times
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(req);

        // Log EVERYTHING
        log.info("======== INCOMING REQUEST ========");
        log.info("METHOD : {}", req.getMethod());
        log.info("URI    : {}", req.getRequestURI());
        log.info("CONTENT-TYPE: {}", req.getContentType());

        // Log all headers
        Enumeration<String> headerNames = req.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            log.info("HEADER : {} = {}", name, req.getHeader(name));
        }

        // Log body
        String body = cached.getBody();
        log.info("BODY   : {}", body.isEmpty() ? "(empty)" : body);
        log.info("==================================");

        chain.doFilter(cached, res);
    }

    /**
     * Wraps request to allow body to be read multiple times.
     * Spring normally consumes the InputStream once — this caches it.
     */
    public static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            InputStream inputStream = request.getInputStream();
            this.cachedBody = inputStream.readAllBytes();
        }

        public String getBody() {
            return new String(cachedBody, StandardCharsets.UTF_8);
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                public int read() { return byteArrayInputStream.read(); }
                public boolean isFinished() { return byteArrayInputStream.available() == 0; }
                public boolean isReady() { return true; }
                public void setReadListener(ReadListener l) {}
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}