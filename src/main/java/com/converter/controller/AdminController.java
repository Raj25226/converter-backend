package com.converter.controller;

import com.converter.dto.AdminStatsResponse;
import com.converter.dto.ApiResponse;
import com.converter.dto.UserPageResponse;
import com.converter.dto.UserUpdateRequest;
import com.converter.exception.AppException;
import com.converter.service.AdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
            @RequestParam(defaultValue = "${admin.default-page-size}") int limit,
            @RequestParam(defaultValue = "${admin.default-offset}") int offset,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(adminService.getUsers(limit, offset, search));
    }

    // PATCH /api/admin/users/:userId
    @PatchMapping("/users/{userId}")
    public ResponseEntity<ApiResponse> updateUser(
            @PathVariable Long userId, @RequestBody UserUpdateRequest req, Authentication auth) {
        if (userId.equals(auth.getPrincipal())
                && (Boolean.FALSE.equals(req.getIsActive())
                        || (req.getRole() != null && !"admin".equalsIgnoreCase(req.getRole())))) {
            throw new AppException(
                    "You cannot suspend or demote your own account.", HttpStatus.CONFLICT);
        }
        adminService.updateUser(userId, req);
        return ResponseEntity.ok(ApiResponse.success("User updated"));
    }

    // DELETE /api/admin/users/:userId
    @DeleteMapping("/users/{userId}")
    public ResponseEntity<ApiResponse> deleteUser(@PathVariable Long userId, Authentication auth) {
        if (userId.equals(auth.getPrincipal()))
            throw new AppException(
                    "You cannot delete your own admin account.", HttpStatus.CONFLICT);
        adminService.deleteUser(userId);
        return ResponseEntity.ok(ApiResponse.success("User deleted"));
    }
}
