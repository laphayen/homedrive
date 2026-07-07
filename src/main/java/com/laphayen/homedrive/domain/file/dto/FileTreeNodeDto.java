package com.laphayen.homedrive.domain.file.dto;

import java.util.List;

public record FileTreeNodeDto(
        String name,
        String path,
        List<FileTreeNodeDto> children
) {
}
