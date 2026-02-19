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
        var analyticsStats = analyticsService.getStats();
        List<ToolStat> toolStats = analyticsService.getToolStats();
        long totalUsers = userRepository.count();

        var data = analyticsStats.getData();
        double rate = (data != null && data.getTotalVisits() > 0)
                ? Math.round((data.getTotalConversions() * 100.0 / data.getTotalVisits()) * 10.0) / 10.0
                : 0.0;

        List<AdminStatsResponse.ToolStatDto> toolStatDtos = toolStats.stream()
                .map(ts -> AdminStatsResponse.ToolStatDto.builder()
                        .tool(ts.getTool())
                        .count(ts.getCount())
                        .build())
                .collect(Collectors.toList());

        return AdminStatsResponse.builder()
                .totalVisits(data != null ? data.getTotalVisits() : 0)
                .totalConversions(data != null ? data.getTotalConversions() : 0)
                .uniqueVisitors(data != null ? data.getUniqueVisitors() : 0)
                .conversionRate(rate)
                .totalUsers(totalUsers)
                .toolStats(toolStatDtos)
                .build();
    }

    public UserPageResponse getUsers(int limit, int offset, String search) {
        limit = Math.min(limit, 100);
        Page<User> page = userRepository.searchUsers(
                search,
                PageRequest.of(offset / limit, limit)
        );

        List<UserPageResponse.UserItem> items = page.getContent().stream()
                .map(UserPageResponse::fromUser)
                .collect(Collectors.toList());

        return UserPageResponse.builder()
                .users(items)
                .total(page.getTotalElements())
                .limit(limit)
                .offset(offset)
                .build();
    }

    @Transactional
    public void updateUser(Long userId, UserUpdateRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        if (req.getRole() != null) {
            try {
                user.setRole(User.Role.valueOf(req.getRole().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new AppException("Invalid role: " + req.getRole(), HttpStatus.BAD_REQUEST);
            }
        }

        if (req.getIsActive() != null) {
            user.setActive(req.getIsActive());
        }

        userRepository.save(user);
        log.info("Admin updated user {}: role={}, active={}", userId, req.getRole(), req.getIsActive());
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));
        userRepository.delete(user);
        log.info("Admin deleted user: {}", userId);
    }
}