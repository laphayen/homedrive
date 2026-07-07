package com.laphayen.homedrive.domain.file.dto;

import java.util.List;

public record FileListResponseDto(
        String path,
        List<FileItemDto> items,
        FileTreeNodeDto tree,
        FileStatsDto stats
) {
}
