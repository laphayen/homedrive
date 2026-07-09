package com.laphayen.homedrive.domain.admin.dto;

import com.laphayen.homedrive.domain.file.dto.FileStatsDto;

import java.time.LocalDateTime;

public record AdminUserResponseDto(
        Long id,
        String username,
        String email,
        String role,
        LocalDateTime createdAt,
        FileStatsDto storage
) {
}
