package com.laphayen.homedrive.domain.file.dto;

public record FileItemDto(
        String name,
        String path,
        String type,
        long size,
        String modifiedAt
) {
}
