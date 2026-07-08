package com.laphayen.homedrive.domain.file.dto;

public record ChunkUploadResponseDto(
        String uploadId,
        int chunkIndex,
        int receivedChunks,
        int totalChunks,
        boolean complete
) {
}
