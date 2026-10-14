package com.converter.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class IpUtil {
    // Forwarded headers must be normalized by a trusted edge, never accepted from clients.
    public String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
