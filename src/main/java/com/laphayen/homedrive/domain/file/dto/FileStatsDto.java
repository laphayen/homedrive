package com.laphayen.homedrive.domain.file.dto;

public record FileStatsDto(
        long totalFiles,
        long totalFolders,
        long totalBytes
) {
}
