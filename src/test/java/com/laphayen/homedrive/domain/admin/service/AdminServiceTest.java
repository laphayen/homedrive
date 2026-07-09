package com.laphayen.homedrive.domain.admin.service;

import com.laphayen.homedrive.domain.admin.dto.AdminUserResponseDto;
import com.laphayen.homedrive.domain.admin.dto.AdminUserRoleRequestDto;
import com.laphayen.homedrive.domain.file.dto.FileStatsDto;
import com.laphayen.homedrive.domain.file.service.FileStorageService;
import com.laphayen.homedrive.domain.user.entity.User;
import com.laphayen.homedrive.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private FileStorageService fileStorageService;

    private AdminService adminService;

    @BeforeEach
    void setUp() {
        adminService = new AdminService(userRepository, fileStorageService);
    }

    @Test
    void updateUserRoleChangesRole() {
        User user = User.builder()
                .id(2L)
                .username("member")
                .email("member@example.com")
                .role("USER")
                .build();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user));
        when(fileStorageService.getStorageStats(user)).thenReturn(new FileStatsDto(0, 0, 0));

        AdminUserResponseDto response = adminService.updateUserRole(
                2L,
                new AdminUserRoleRequestDto("admin"),
                "nathan"
        );

        assertThat(user.getRole()).isEqualTo("ADMIN");
        assertThat(response.role()).isEqualTo("ADMIN");
    }

    @Test
    void updateUserRoleRejectsSelfDemotion() {
        User admin = User.builder()
                .id(1L)
                .username("nathan")
                .email("nathan@homedrive.local")
                .role("ADMIN")
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> adminService.updateUserRole(
                1L,
                new AdminUserRoleRequestDto("USER"),
                "nathan"
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("현재 관리자 계정은 강등할 수 없습니다.");
    }

    @Test
    void deleteUserRemovesStorageBeforeRecord() {
        User user = User.builder()
                .id(2L)
                .username("member")
                .email("member@example.com")
                .role("USER")
                .build();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        adminService.deleteUser(2L, "nathan");

        verify(fileStorageService).deleteStorage(user);
        verify(userRepository).delete(user);
    }
}
