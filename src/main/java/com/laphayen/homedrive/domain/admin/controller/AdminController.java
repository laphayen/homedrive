package com.laphayen.homedrive.domain.admin.controller;

import com.laphayen.homedrive.domain.admin.dto.AdminSystemResponseDto;
import com.laphayen.homedrive.domain.admin.dto.AdminUserResponseDto;
import com.laphayen.homedrive.domain.admin.dto.AdminUserRoleRequestDto;
import com.laphayen.homedrive.domain.admin.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/users")
    public List<AdminUserResponseDto> users() {
        return adminService.getUsers();
    }

    @PatchMapping("/users/{userId}/role")
    public AdminUserResponseDto updateUserRole(
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserRoleRequestDto request,
            Authentication authentication
    ) {
        return adminService.updateUserRole(userId, request, authentication.getName());
    }

    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        adminService.deleteUser(userId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/system")
    public AdminSystemResponseDto system() {
        return adminService.getSystem();
    }

    @DeleteMapping("/system/uploads")
    public ResponseEntity<Void> clearUploadSessions() {
        adminService.clearUploadSessions();
        return ResponseEntity.noContent().build();
    }
}
