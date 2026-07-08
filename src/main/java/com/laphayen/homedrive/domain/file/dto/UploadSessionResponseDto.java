package com.laphayen.homedrive.domain.file.dto;

public record UploadSessionResponseDto(
        String uploadId,
        String path,
        String filename,
        long totalSize,
        int totalChunks,
        long chunkSize
) {
}
