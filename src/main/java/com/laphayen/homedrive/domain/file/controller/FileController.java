package com.laphayen.homedrive.domain.file.controller;

import com.laphayen.homedrive.domain.file.dto.CreateFolderRequestDto;
import com.laphayen.homedrive.domain.file.dto.FileItemDto;
import com.laphayen.homedrive.domain.file.dto.FileListResponseDto;
import com.laphayen.homedrive.domain.file.dto.StoredFileResource;
import com.laphayen.homedrive.domain.file.service.FileStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    @GetMapping
    public FileListResponseDto list(
            @RequestParam(defaultValue = "/") String path,
            Authentication authentication
    ) {
        return fileStorageService.list(authentication.getName(), path);
    }

    @PostMapping("/folders")
    public FileItemDto createFolder(
            @Valid @RequestBody CreateFolderRequestDto request,
            Authentication authentication
    ) {
        return fileStorageService.createFolder(authentication.getName(), request.getPath(), request.getName());
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<FileItemDto> upload(
            @RequestParam(defaultValue = "/") String path,
            @RequestPart("files") MultipartFile[] files,
            Authentication authentication
    ) {
        return fileStorageService.upload(authentication.getName(), path, files);
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(
            @RequestParam String path,
            Authentication authentication
    ) {
        StoredFileResource file = fileStorageService.download(authentication.getName(), path);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.resource());
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(
            @RequestParam String path,
            Authentication authentication
    ) {
        fileStorageService.delete(authentication.getName(), path);
        return ResponseEntity.noContent().build();
    }
}
