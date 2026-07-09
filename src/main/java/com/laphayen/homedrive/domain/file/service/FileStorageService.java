package com.laphayen.homedrive.domain.file.service;

import com.laphayen.homedrive.domain.file.dto.ChunkUploadResponseDto;
import com.laphayen.homedrive.domain.file.dto.FileItemDto;
import com.laphayen.homedrive.domain.file.dto.FileListResponseDto;
import com.laphayen.homedrive.domain.file.dto.FileStatsDto;
import com.laphayen.homedrive.domain.file.dto.FileTreeNodeDto;
import com.laphayen.homedrive.domain.file.dto.InitiateUploadRequestDto;
import com.laphayen.homedrive.domain.file.dto.StoredFileResource;
import com.laphayen.homedrive.domain.file.dto.UploadSessionResponseDto;
import com.laphayen.homedrive.domain.user.entity.User;
import com.laphayen.homedrive.domain.user.repository.UserRepository;
import com.laphayen.homedrive.global.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final int TREE_DEPTH_LIMIT = 8;

    private final UserRepository userRepository;

    @Value("${file.base-dir}")
    private String baseDir;

    @Value("${file.chunk-temp-dir}")
    private String chunkTempDir;

    @Value("${file.max-chunk-size:10MB}")
    private DataSize maxChunkSize;

    public FileStatsDto getStorageStats(User user) {
        Path root = ensureUserRoot(user);
        return buildStats(root);
    }

    public void deleteStorage(User user) {
        deleteRecursively(userRoot(user));
        deleteRecursively(chunkRoot(user));
    }

    public long countActiveUploadSessions() {
        Path root = Path.of(chunkTempDir).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            return 0;
        }

        try (Stream<Path> stream = Files.find(root, 4, (path, attrs) ->
                attrs.isRegularFile() && "upload.properties".equals(path.getFileName().toString()))) {
            return stream.count();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to count uploads.", e);
        }
    }

    public void clearUploadSessions() {
        Path root = Path.of(chunkTempDir).toAbsolutePath().normalize();
        deleteRecursively(root);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to prepare upload storage.", e);
        }
    }

    public FileListResponseDto list(String username, String path) {
        User user = findUser(username);
        Path root = ensureUserRoot(user);
        Path directory = resolve(root, path);

        if (!Files.isDirectory(directory)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Directory not found.");
        }

        try (Stream<Path> stream = Files.list(directory)) {
            List<FileItemDto> items = stream
                    .map(itemPath -> toItem(root, itemPath))
                    .sorted(Comparator
                            .comparing((FileItemDto item) -> !"folder".equals(item.type()))
                            .thenComparing(FileItemDto::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();

            return new FileListResponseDto(
                    toBrowserPath(root, directory),
                    items,
                    buildTree(root, root, 0),
                    buildStats(root)
            );
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to list files.", e);
        }
    }

    public FileItemDto createFolder(String username, String path, String name) {
        User user = findUser(username);
        Path root = ensureUserRoot(user);
        Path parent = resolve(root, path);

        if (!Files.isDirectory(parent)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Directory not found.");
        }

        Path directory = resolve(root, joinPath(toBrowserPath(root, parent), sanitizeName(name)));

        try {
            Files.createDirectories(directory);
            return toItem(root, directory);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to create folder.", e);
        }
    }

    public List<FileItemDto> upload(String username, String path, MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No files selected.");
        }

        User user = findUser(username);
        Path root = ensureUserRoot(user);
        Path directory = resolve(root, path);

        if (!Files.isDirectory(directory)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Directory not found.");
        }

        List<FileItemDto> saved = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null) {
                continue;
            }

            String filename = sanitizeName(file.getOriginalFilename());
            Path target = resolve(root, joinPath(toBrowserPath(root, directory), filename));

            try {
                Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
                saved.add(toItem(root, target));
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to upload file.", e);
            }
        }

        return saved;
    }

    public UploadSessionResponseDto initiateUpload(String username, InitiateUploadRequestDto request) {
        if (request.getTotalSize() > 0 && request.getTotalChunks() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Total chunks are required.");
        }
        if (request.getTotalSize() == 0 && request.getTotalChunks() > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty files must not include chunks.");
        }

        User user = findUser(username);
        Path root = ensureUserRoot(user);
        Path directory = resolve(root, request.getPath());

        if (!Files.isDirectory(directory)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Directory not found.");
        }

        String uploadId = UUID.randomUUID().toString();
        String filename = sanitizeName(request.getFilename());
        String targetPath = toBrowserPath(root, directory);
        Path sessionDirectory = uploadDirectory(user, uploadId);

        try {
            Files.createDirectories(sessionDirectory);

            Properties metadata = new Properties();
            metadata.setProperty("userId", String.valueOf(user.getId()));
            metadata.setProperty("path", targetPath);
            metadata.setProperty("filename", filename);
            metadata.setProperty("totalSize", String.valueOf(request.getTotalSize()));
            metadata.setProperty("totalChunks", String.valueOf(request.getTotalChunks()));
            metadata.setProperty("createdAt", String.valueOf(System.currentTimeMillis()));

            try (OutputStream outputStream = Files.newOutputStream(metadataPath(sessionDirectory), StandardOpenOption.CREATE_NEW)) {
                metadata.store(outputStream, "HomeDrive chunked upload");
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to prepare upload.", e);
        }

        return new UploadSessionResponseDto(
                uploadId,
                targetPath,
                filename,
                request.getTotalSize(),
                request.getTotalChunks(),
                maxChunkSize.toBytes()
        );
    }

    public ChunkUploadResponseDto uploadChunk(String username, String uploadId, int chunkIndex, MultipartFile chunk) {
        User user = findUser(username);
        Properties metadata = readUploadMetadata(user, uploadId);
        int totalChunks = readInt(metadata, "totalChunks");

        if (totalChunks <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload does not accept chunks.");
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid chunk index.");
        }
        if (chunk == null || chunk.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chunk is required.");
        }
        if (chunk.getSize() > maxChunkSize.toBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Chunk is too large.");
        }

        Path sessionDirectory = uploadDirectory(user, uploadId);
        Path chunkPath = chunkPath(sessionDirectory, chunkIndex);

        try {
            Files.copy(chunk.getInputStream(), chunkPath, StandardCopyOption.REPLACE_EXISTING);
            int receivedChunks = countReceivedChunks(sessionDirectory, totalChunks);

            return new ChunkUploadResponseDto(
                    uploadId,
                    chunkIndex,
                    receivedChunks,
                    totalChunks,
                    receivedChunks == totalChunks
            );
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to save chunk.", e);
        }
    }

    public FileItemDto completeUpload(String username, String uploadId) {
        User user = findUser(username);
        Path root = ensureUserRoot(user);
        Properties metadata = readUploadMetadata(user, uploadId);
        int totalChunks = readInt(metadata, "totalChunks");
        long expectedSize = readLong(metadata, "totalSize");
        Path sessionDirectory = uploadDirectory(user, uploadId);

        if (countReceivedChunks(sessionDirectory, totalChunks) != totalChunks) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload is incomplete.");
        }

        Path directory = resolve(root, metadata.getProperty("path"));
        if (!Files.isDirectory(directory)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Directory not found.");
        }

        String filename = sanitizeName(metadata.getProperty("filename"));
        Path target = resolve(root, joinPath(toBrowserPath(root, directory), filename));
        Path assembling = target.resolveSibling(target.getFileName() + ".uploading-" + uploadId);

        try {
            try (OutputStream outputStream = Files.newOutputStream(
                    assembling,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                for (int index = 0; index < totalChunks; index++) {
                    Files.copy(chunkPath(sessionDirectory, index), outputStream);
                }
            }

            long actualSize = Files.size(assembling);
            if (actualSize != expectedSize) {
                Files.deleteIfExists(assembling);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Uploaded size does not match.");
            }

            moveCompletedFile(assembling, target);
            deleteRecursively(sessionDirectory);

            return toItem(root, target);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(assembling);
            } catch (IOException ignored) {
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to complete upload.", e);
        }
    }

    public void cancelUpload(String username, String uploadId) {
        User user = findUser(username);
        readUploadMetadata(user, uploadId);
        deleteRecursively(uploadDirectory(user, uploadId));
    }

    public StoredFileResource download(String username, String path) {
        User user = findUser(username);
        Path root = ensureUserRoot(user);
        Path file = resolve(root, path);

        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found.");
        }

        try {
            Resource resource = new UrlResource(file.toUri());
            String contentType = Files.probeContentType(file);

            return new StoredFileResource(
                    file.getFileName().toString(),
                    contentType == null ? "application/octet-stream" : contentType,
                    Files.size(file),
                    resource
            );
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read file.", e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to prepare file.", e);
        }
    }

    public void delete(String username, String path) {
        User user = findUser(username);
        Path root = ensureUserRoot(user);
        Path target = resolve(root, path);

        if (target.equals(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Root directory cannot be deleted.");
        }
        if (!Files.exists(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found.");
        }

        try {
            if (Files.isDirectory(target)) {
                deleteRecursively(target);
            } else {
                Files.deleteIfExists(target);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to delete item.", e);
        }
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found."));
    }

    private Path ensureUserRoot(User user) {
        Path root = userRoot(user);
        try {
            Files.createDirectories(root);
            return root;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to prepare storage.", e);
        }
    }

    private Path uploadDirectory(User user, String uploadId) {
        validateUploadId(uploadId);
        return ensureChunkRoot(user).resolve(uploadId).normalize();
    }

    private Path ensureChunkRoot(User user) {
        Path root = chunkRoot(user);
        try {
            Files.createDirectories(root);
            return root;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to prepare upload storage.", e);
        }
    }

    private Path userRoot(User user) {
        return Path.of(baseDir)
                .toAbsolutePath()
                .normalize()
                .resolve("user-" + user.getId())
                .normalize();
    }

    private Path chunkRoot(User user) {
        return Path.of(chunkTempDir)
                .toAbsolutePath()
                .normalize()
                .resolve("user-" + user.getId())
                .normalize();
    }

    private void validateUploadId(String uploadId) {
        try {
            UUID.fromString(uploadId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid upload id.");
        }
    }

    private Path metadataPath(Path sessionDirectory) {
        return sessionDirectory.resolve("upload.properties");
    }

    private Path chunkPath(Path sessionDirectory, int index) {
        return sessionDirectory.resolve("chunk-" + index + ".part");
    }

    private Properties readUploadMetadata(User user, String uploadId) {
        Path sessionDirectory = uploadDirectory(user, uploadId);
        Path metadataFile = metadataPath(sessionDirectory);

        if (!Files.isRegularFile(metadataFile)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload not found.");
        }

        try (var inputStream = Files.newInputStream(metadataFile)) {
            Properties metadata = new Properties();
            metadata.load(inputStream);

            if (!String.valueOf(user.getId()).equals(metadata.getProperty("userId"))) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload not found.");
            }

            return metadata;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read upload.", e);
        }
    }

    private int readInt(Properties metadata, String key) {
        try {
            return Integer.parseInt(metadata.getProperty(key));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload metadata is invalid.");
        }
    }

    private long readLong(Properties metadata, String key) {
        try {
            return Long.parseLong(metadata.getProperty(key));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload metadata is invalid.");
        }
    }

    private int countReceivedChunks(Path sessionDirectory, int totalChunks) {
        int received = 0;
        for (int index = 0; index < totalChunks; index++) {
            if (Files.isRegularFile(chunkPath(sessionDirectory, index))) {
                received++;
            }
        }
        return received;
    }

    private void moveCompletedFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRecursively(Path path) {
        try {
            if (!Files.exists(path)) {
                return;
            }

            try (Stream<Path> stream = Files.walk(path)) {
                List<Path> targets = stream.sorted(Comparator.reverseOrder()).toList();
                for (Path item : targets) {
                    Files.deleteIfExists(item);
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to delete item.", e);
        }
    }

    private Path resolve(Path root, String browserPath) {
        String normalized = normalizeBrowserPath(browserPath);
        String relative = normalized.equals("/") ? "" : normalized.substring(1);
        Path resolved = root.resolve(relative).normalize();

        if (!resolved.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path.");
        }

        return resolved;
    }

    private String normalizeBrowserPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) {
            return "/";
        }

        String cleaned = path.trim().replace('\\', '/');
        String[] segments = cleaned.split("/");
        List<String> safeSegments = new ArrayList<>();

        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path.");
            }
            safeSegments.add(segment);
        }

        return safeSegments.isEmpty() ? "/" : "/" + String.join("/", safeSegments);
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required.");
        }

        String filename = Path.of(name.replace('\\', '/')).getFileName().toString().trim();
        if (filename.isBlank() || ".".equals(filename) || "..".equals(filename)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid name.");
        }

        return filename;
    }

    private String joinPath(String parent, String name) {
        if ("/".equals(parent)) {
            return "/" + name;
        }
        return parent + "/" + name;
    }

    private FileItemDto toItem(Path root, Path path) {
        try {
            boolean directory = Files.isDirectory(path);
            return new FileItemDto(
                    path.getFileName().toString(),
                    toBrowserPath(root, path),
                    directory ? "folder" : "file",
                    directory ? 0 : Files.size(path),
                    Files.getLastModifiedTime(path).toInstant().toString()
            );
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read item.", e);
        }
    }

    private FileTreeNodeDto buildTree(Path root, Path directory, int depth) {
        if (depth >= TREE_DEPTH_LIMIT) {
            return new FileTreeNodeDto(toTreeName(root, directory), toBrowserPath(root, directory), List.of());
        }

        try (Stream<Path> stream = Files.list(directory)) {
            List<FileTreeNodeDto> children = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .map(child -> buildTree(root, child, depth + 1))
                    .toList();

            return new FileTreeNodeDto(toTreeName(root, directory), toBrowserPath(root, directory), children);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to build tree.", e);
        }
    }

    private String toTreeName(Path root, Path directory) {
        return root.equals(directory) ? "Home" : directory.getFileName().toString();
    }

    private String toBrowserPath(Path root, Path path) {
        if (root == null || path == null || root.equals(path)) {
            return "/";
        }

        String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
        return relative.isBlank() ? "/" : "/" + relative;
    }

    private FileStatsDto buildStats(Path root) {
        long files = 0;
        long folders = 0;
        long bytes = 0;

        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (path.equals(root)) {
                    continue;
                }
                if (Files.isDirectory(path)) {
                    folders++;
                } else {
                    files++;
                    bytes += Files.size(path);
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to calculate storage.", e);
        }

        return new FileStatsDto(files, folders, bytes);
    }
}
