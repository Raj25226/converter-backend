package com.converter.controller;

import com.converter.service.QuotaService;
import com.converter.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/convert")
public class QuotaController {
    private final QuotaService quotaService;
    private final IpUtil ipUtil;

    @Value("${conversion.guest-daily-limit}")
    private int guestLimit;

    @Value("${conversion.user-daily-limit}")
    private int userLimit;

    public QuotaController(QuotaService quotaService, IpUtil ipUtil) {
        this.quotaService = quotaService;
        this.ipUtil = ipUtil;
    }

    @GetMapping("/quota")
    public Map<String, Integer> quota(Authentication auth, HttpServletRequest request) {
        Long id = auth != null && auth.getPrincipal() instanceof Long value ? value : null;
        int limit = id == null ? guestLimit : userLimit;
        int used = quotaService.used(id, ipUtil.getClientIp(request));
        return Map.of("used", used, "limit", limit, "remaining", Math.max(0, limit - used));
    }
}
