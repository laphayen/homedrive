package com.laphayen.homedrive.domain.file.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class InitiateUploadRequestDto {

    private String path = "/";

    @NotBlank(message = "Filename is required.")
    private String filename;

    @Min(value = 0, message = "Total size must be zero or greater.")
    private long totalSize;

    @Min(value = 0, message = "Total chunks must be zero or greater.")
    private int totalChunks;
}
