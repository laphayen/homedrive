package com.laphayen.homedrive.domain.file.service;

import com.laphayen.homedrive.domain.file.dto.FileItemDto;
import com.laphayen.homedrive.domain.file.dto.FileListResponseDto;
import com.laphayen.homedrive.domain.file.dto.InitiateUploadRequestDto;
import com.laphayen.homedrive.domain.file.dto.UploadSessionResponseDto;
import com.laphayen.homedrive.domain.user.entity.User;
import com.laphayen.homedrive.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private UserRepository userRepository;

    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() {
        fileStorageService = new FileStorageService(userRepository);
        ReflectionTestUtils.setField(fileStorageService, "baseDir", tempDir.toString());
        ReflectionTestUtils.setField(fileStorageService, "chunkTempDir", tempDir.resolve("chunks").toString());
        ReflectionTestUtils.setField(fileStorageService, "maxChunkSize", DataSize.ofMegabytes(10));

        User user = User.builder()
                .id(7L)
                .username("tester")
                .email("tester@example.com")
                .role("USER")
                .build();
        when(userRepository.findByUsername("tester")).thenReturn(Optional.of(user));
    }

    @Test
    void uploadAndListFilesInsideUserStorage() {
        fileStorageService.createFolder("tester", "/", "docs");
        fileStorageService.upload("tester", "/docs", new MockMultipartFile[]{
                new MockMultipartFile("files", "note.txt", "text/plain", "hello".getBytes())
        });

        FileListResponseDto response = fileStorageService.list("tester", "/docs");

        assertThat(response.path()).isEqualTo("/docs");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).name()).isEqualTo("note.txt");
        assertThat(response.items().get(0).path()).isEqualTo("/docs/note.txt");
        assertThat(response.stats().totalFiles()).isEqualTo(1);
    }

    @Test
    void chunkedUploadAssemblesFile() throws Exception {
        fileStorageService.createFolder("tester", "/", "docs");
        InitiateUploadRequestDto request = new InitiateUploadRequestDto();
        ReflectionTestUtils.setField(request, "path", "/docs");
        ReflectionTestUtils.setField(request, "filename", "large.txt");
        ReflectionTestUtils.setField(request, "totalSize", 11L);
        ReflectionTestUtils.setField(request, "totalChunks", 2);

        UploadSessionResponseDto session = fileStorageService.initiateUpload("tester", request);
        fileStorageService.uploadChunk("tester", session.uploadId(), 0,
                new MockMultipartFile("chunk", "chunk-0", "application/octet-stream", "hello ".getBytes(StandardCharsets.UTF_8)));
        fileStorageService.uploadChunk("tester", session.uploadId(), 1,
                new MockMultipartFile("chunk", "chunk-1", "application/octet-stream", "world".getBytes(StandardCharsets.UTF_8)));

        FileItemDto completed = fileStorageService.completeUpload("tester", session.uploadId());

        assertThat(completed.path()).isEqualTo("/docs/large.txt");
        assertThat(completed.size()).isEqualTo(11);
        assertThat(Files.readString(tempDir.resolve("user-7/docs/large.txt"))).isEqualTo("hello world");
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> fileStorageService.list("tester", "../outside"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid path");
    }
}
