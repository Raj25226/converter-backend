package com.converter.controller;

import com.converter.service.QuotaService;
import com.converter.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/convert")
public class QuotaController {
    private final QuotaService quotas;
    private final IpUtil ips;
    @Value("${conversion.guest-daily-limit:2}") private int guestLimit;
    @Value("${conversion.user-daily-limit:10}") private int userLimit;
    public QuotaController(QuotaService quotas, IpUtil ips) { this.quotas = quotas; this.ips = ips; }
    @GetMapping("/quota")
    public Map<String, Integer> quota(Authentication auth, HttpServletRequest request) {
        Long id = auth != null && auth.getPrincipal() instanceof Long value ? value : null;
        int limit = id == null ? guestLimit : userLimit;
        int used = quotas.used(id, ips.getClientIp(request));
        return Map.of("used", used, "limit", limit, "remaining", Math.max(0, limit - used));
    }
}
