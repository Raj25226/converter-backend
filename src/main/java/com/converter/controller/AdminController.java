package com.converter.controller;

import com.converter.dto.AdminStatsResponse;
import com.converter.dto.ApiResponse;
import com.converter.dto.UserPageResponse;
import com.converter.dto.UserUpdateRequest;
import com.converter.service.AdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;

    // GET /api/admin/stats
    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminService.getStats());
    }

    // GET /api/admin/users?limit=20&offset=0&search=
    @GetMapping("/users")
    public ResponseEntity<UserPageResponse> getUsers(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(adminService.getUsers(limit, offset, search));
    }

    // PATCH /api/admin/users/:userId
    @PatchMapping("/users/{userId}")
    public ResponseEntity<ApiResponse> updateUser(
            @PathVariable Long userId,
            @RequestBody UserUpdateRequest req) {
        adminService.updateUser(userId, req);
        return ResponseEntity.ok(ApiResponse.success("User updated"));
    }

    // DELETE /api/admin/users/:userId
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<ApiResponse> deleteUser(@PathVariable Long userId) {
        adminService.deleteUser(userId);
        return ResponseEntity.ok(ApiResponse.success("User deleted"));
    }
}