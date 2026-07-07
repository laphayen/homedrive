package com.laphayen.homedrive.domain.file.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class CreateFolderRequestDto {

    private String path = "/";

    @NotBlank(message = "Folder name is required.")
    private String name;
}
