package com.laphayen.homedrive.domain.file.dto;

import org.springframework.core.io.Resource;

public record StoredFileResource(
        String filename,
        String contentType,
        long size,
        Resource resource
) {
}
