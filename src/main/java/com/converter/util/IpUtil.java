package com.converter.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class IpUtil {

    public String getClientIp(HttpServletRequest request) {
        String[] headers = {
                "X-Forwarded-For",
                "X-Real-IP",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP",
                "HTTP_X_FORWARDED_FOR",
                "HTTP_X_FORWARDED",
                "HTTP_FORWARDED_FOR",
                "HTTP_FORWARDED"
        };

        for (String header : headers) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // Take the first IP from comma-separated list (in case of proxy chain)
                return ip.split(",")[0].trim();
            }
        }

        return request.getRemoteAddr();
    }
}