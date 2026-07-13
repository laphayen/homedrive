package com.laphayen.homedrive.domain.file.dto;

public record UploadSessionStatusResponseDto(
        String uploadId,
        String path,
        String filename,
        long totalSize,
        int totalChunks,
        int receivedChunks,
        boolean complete
) {
}
