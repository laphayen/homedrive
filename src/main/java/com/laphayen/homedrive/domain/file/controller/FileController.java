package com.laphayen.homedrive.domain.file.controller;

import com.laphayen.homedrive.domain.file.dto.ChunkUploadResponseDto;
import com.laphayen.homedrive.domain.file.dto.CreateFolderRequestDto;
import com.laphayen.homedrive.domain.file.dto.FileItemDto;
import com.laphayen.homedrive.domain.file.dto.FileListResponseDto;
import com.laphayen.homedrive.domain.file.dto.InitiateUploadRequestDto;
import com.laphayen.homedrive.domain.file.dto.StoredFileResource;
import com.laphayen.homedrive.domain.file.dto.UploadSessionResponseDto;
import com.laphayen.homedrive.domain.file.dto.UploadSessionStatusResponseDto;
import com.laphayen.homedrive.domain.file.service.FileStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
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

    @PostMapping("/uploads")
    public UploadSessionResponseDto initiateUpload(
            @Valid @RequestBody InitiateUploadRequestDto request,
            Authentication authentication
    ) {
        return fileStorageService.initiateUpload(authentication.getName(), request);
    }

    @PostMapping(value = "/uploads/{uploadId}/chunks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChunkUploadResponseDto uploadChunk(
            @PathVariable String uploadId,
            @RequestParam int index,
            @RequestPart("chunk") MultipartFile chunk,
            Authentication authentication
    ) {
        return fileStorageService.uploadChunk(authentication.getName(), uploadId, index, chunk);
    }

    @GetMapping("/uploads/{uploadId}")
    public UploadSessionStatusResponseDto uploadStatus(
            @PathVariable String uploadId,
            Authentication authentication
    ) {
        return fileStorageService.uploadStatus(authentication.getName(), uploadId);
    }

    @PostMapping("/uploads/{uploadId}/complete")
    public FileItemDto completeUpload(
            @PathVariable String uploadId,
            Authentication authentication
    ) {
        return fileStorageService.completeUpload(authentication.getName(), uploadId);
    }

    @DeleteMapping("/uploads/{uploadId}")
    public ResponseEntity<Void> cancelUpload(
            @PathVariable String uploadId,
            Authentication authentication
    ) {
        fileStorageService.cancelUpload(authentication.getName(), uploadId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(
            @RequestParam String path,
            @RequestHeader HttpHeaders requestHeaders,
            Authentication authentication
    ) {
        StoredFileResource file = fileStorageService.download(authentication.getName(), path);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(MediaType.parseMediaType(file.contentType()));
        responseHeaders.setContentDisposition(disposition);
        responseHeaders.set(HttpHeaders.ACCEPT_RANGES, "bytes");

        List<HttpRange> ranges = requestHeaders.getRange();
        if (ranges.isEmpty()) {
            return ResponseEntity.ok()
                    .headers(responseHeaders)
                    .contentLength(file.size())
                    .body(file.resource());
        }

        DownloadRange range = createDownloadRange(file, ranges.get(0));
        long start = range.start();
        long end = range.end();
        responseHeaders.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + file.size());

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .headers(responseHeaders)
                .contentLength(range.count())
                .body(createRangeResource(file, range));
    }

    private DownloadRange createDownloadRange(StoredFileResource file, HttpRange range) {
        try {
            long start = range.getRangeStart(file.size());
            long end = range.getRangeEnd(file.size());
            long count = end - start + 1;

            return new DownloadRange(start, end, count);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Invalid download range.", e);
        }
    }

    private Resource createRangeResource(StoredFileResource file, DownloadRange range) {
        try {
            InputStream inputStream = file.resource().getInputStream();
            inputStream.skipNBytes(range.start());
            return new InputStreamResource(new LimitedInputStream(inputStream, range.count())) {
                @Override
                public long contentLength() {
                    return range.count();
                }
            };
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read download range.", e);
        }
    }

    private record DownloadRange(long start, long end, long count) {
    }

    private static class LimitedInputStream extends InputStream {

        private final InputStream delegate;
        private long remaining;

        private LimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }

            int value = delegate.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }

            int read = delegate.read(buffer, offset, (int) Math.min(length, remaining));
            if (read != -1) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
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
