package com.converter.service;

import com.converter.dto.AdminStatsResponse;
import com.converter.dto.UserPageResponse;
import com.converter.dto.UserUpdateRequest;
import com.converter.entity.ToolStat;
import com.converter.entity.User;
import com.converter.exception.AppException;
import com.converter.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {

    private final UserRepository userRepository;
    private final AnalyticsService analyticsService;

    public AdminStatsResponse getStats() {
        log.info("Admin: fetching dashboard stats");

        var analyticsStats = analyticsService.getStats();
        List<ToolStat> toolStats = analyticsService.getToolStats();
        long totalUsers = userRepository.count();

        var data = analyticsStats.getData();
        long visits      = data != null ? data.getTotalVisits()      : 0;
        long conversions = data != null ? data.getTotalConversions() : 0;
        long unique      = data != null ? data.getUniqueVisitors()   : 0;
        double rate = visits > 0
                ? Math.round((conversions * 100.0 / visits) * 10.0) / 10.0
                : 0.0;

        List<AdminStatsResponse.ToolStatDto> toolStatDtos = toolStats.stream()
                .map(ts -> AdminStatsResponse.ToolStatDto.builder()
                        .tool(ts.getTool())
                        .count(ts.getCount())
                        .build())
                .collect(Collectors.toList());

        log.info("Admin stats: users={}, visits={}, conversions={}, unique={}, rate={}%",
                totalUsers, visits, conversions, unique, rate);

        return AdminStatsResponse.builder()
                .status("success")
                .totalVisits(visits)
                .totalConversions(conversions)
                .uniqueVisitors(unique)
                .conversionRate(rate)
                .totalUsers(totalUsers)
                .toolStats(toolStatDtos)
                .build();
    }

    public UserPageResponse getUsers(int limit, int offset, String search) {
        limit = Math.min(Math.max(limit, 1), 100);
        int page = offset / limit;

        // ✅ Normalize null search to empty string for the JPQL query
        String normalizedSearch = (search == null) ? "" : search.trim();

        log.info("Admin: list users — page={}, limit={}, search=[{}]", page, limit, normalizedSearch);

        Page<User> userPage = userRepository.searchUsers(normalizedSearch, PageRequest.of(page, limit));

        List<UserPageResponse.UserItem> items = userPage.getContent().stream()
                .map(UserPageResponse::fromUser)
                .collect(Collectors.toList());

        log.info("Admin: returned {} users (total={})", items.size(), userPage.getTotalElements());

        return UserPageResponse.builder()
                .status("success")
                .users(items)
                .total(userPage.getTotalElements())
                .limit(limit)
                .offset(offset)
                .build();
    }

    @Transactional
    public void updateUser(Long userId, UserUpdateRequest req) {
        log.info("Admin: update user=[{}] role=[{}] active=[{}]", userId, req.getRole(), req.getIsActive());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Admin: user=[{}] not found for update", userId);
                    return new AppException("User not found", HttpStatus.NOT_FOUND);
                });

        if (req.getRole() != null && !req.getRole().isBlank()) {
            try {
                user.setRole(User.Role.valueOf(req.getRole().toUpperCase()));
                log.info("Admin: user=[{}] role changed to [{}]", userId, req.getRole());
            } catch (IllegalArgumentException e) {
                log.warn("Admin: invalid role [{}] for user=[{}]", req.getRole(), userId);
                throw new AppException("Invalid role: '" + req.getRole() + "'. Must be USER or ADMIN.",
                        HttpStatus.BAD_REQUEST);
            }
        }

        if (req.getIsActive() != null) {
            user.setActive(req.getIsActive());
            log.info("Admin: user=[{}] active set to [{}]", userId, req.getIsActive());
        }

        userRepository.save(user);
        log.info("Admin: user=[{}] updated successfully", userId);
    }

    @Transactional
    public void deleteUser(Long userId) {
        log.info("Admin: delete user=[{}]", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Admin: user=[{}] not found for delete", userId);
                    return new AppException("User not found", HttpStatus.NOT_FOUND);
                });

        userRepository.delete(user);
        log.info("Admin: user=[{}] email=[{}] deleted", userId, user.getEmail());
    }
}