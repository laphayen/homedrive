package com.laphayen.homedrive.domain.admin.service;

import com.laphayen.homedrive.domain.admin.dto.AdminSystemResponseDto;
import com.laphayen.homedrive.domain.admin.dto.AdminUserResponseDto;
import com.laphayen.homedrive.domain.admin.dto.AdminUserRoleRequestDto;
import com.laphayen.homedrive.domain.file.dto.FileStatsDto;
import com.laphayen.homedrive.domain.file.service.FileStorageService;
import com.laphayen.homedrive.domain.user.entity.User;
import com.laphayen.homedrive.domain.user.repository.UserRepository;
import com.laphayen.homedrive.global.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminService {

    private static final Set<String> ALLOWED_ROLES = Set.of("USER", "ADMIN");

    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;

    @Transactional(readOnly = true)
    public List<AdminUserResponseDto> getUsers() {
        return userRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .map(this::toUserResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminSystemResponseDto getSystem() {
        List<User> users = userRepository.findAll();
        long adminUsers = users.stream()
                .filter(user -> "ADMIN".equals(user.getRole()))
                .count();

        FileStatsDto totalStorage = users.stream()
                .map(fileStorageService::getStorageStats)
                .reduce(new FileStatsDto(0, 0, 0), (left, right) -> new FileStatsDto(
                        left.totalFiles() + right.totalFiles(),
                        left.totalFolders() + right.totalFolders(),
                        left.totalBytes() + right.totalBytes()
                ));

        return new AdminSystemResponseDto(
                users.size(),
                adminUsers,
                users.size() - adminUsers,
                totalStorage.totalFiles(),
                totalStorage.totalFolders(),
                totalStorage.totalBytes(),
                fileStorageService.countActiveUploadSessions()
        );
    }

    @Transactional
    public AdminUserResponseDto updateUserRole(Long userId, AdminUserRoleRequestDto request, String currentUsername) {
        User user = findUser(userId);
        String role = normalizeRole(request.role());

        if (user.getUsername().equals(currentUsername) && !"ADMIN".equals(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "현재 관리자 계정은 강등할 수 없습니다.");
        }

        user.setRole(role);
        return toUserResponse(user);
    }

    @Transactional
    public void deleteUser(Long userId, String currentUsername) {
        User user = findUser(userId);

        if (user.getUsername().equals(currentUsername)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "현재 로그인한 관리자 계정은 삭제할 수 없습니다.");
        }

        fileStorageService.deleteStorage(user);
        userRepository.delete(user);
    }

    public void clearUploadSessions() {
        fileStorageService.clearUploadSessions();
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found."));
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_ROLES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 역할입니다.");
        }
        return normalized;
    }

    private AdminUserResponseDto toUserResponse(User user) {
        return new AdminUserResponseDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getCreatedAt(),
                fileStorageService.getStorageStats(user)
        );
    }
}
