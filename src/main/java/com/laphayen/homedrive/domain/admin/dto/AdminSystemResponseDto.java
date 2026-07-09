package com.laphayen.homedrive.domain.admin.dto;

public record AdminSystemResponseDto(
        long totalUsers,
        long adminUsers,
        long regularUsers,
        long totalFiles,
        long totalFolders,
        long totalBytes,
        long activeUploadSessions
) {
}
