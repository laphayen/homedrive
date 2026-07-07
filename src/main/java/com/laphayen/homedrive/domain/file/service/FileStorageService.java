package com.laphayen.homedrive.domain.file.service;

import com.laphayen.homedrive.domain.file.dto.FileItemDto;
import com.laphayen.homedrive.domain.file.dto.FileListResponseDto;
import com.laphayen.homedrive.domain.file.dto.FileStatsDto;
import com.laphayen.homedrive.domain.file.dto.FileTreeNodeDto;
import com.laphayen.homedrive.domain.file.dto.StoredFileResource;
import com.laphayen.homedrive.domain.user.entity.User;
import com.laphayen.homedrive.domain.user.repository.UserRepository;
import com.laphayen.homedrive.global.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final int TREE_DEPTH_LIMIT = 8;

    private final UserRepository userRepository;

    @Value("${file.base-dir}")
    private String baseDir;

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
            if (file == null || file.isEmpty()) {
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
                try (Stream<Path> stream = Files.walk(target)) {
                    List<Path> targets = stream.sorted(Comparator.reverseOrder()).toList();
                    for (Path item : targets) {
                        Files.deleteIfExists(item);
                    }
                }
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
        Path root = Path.of(baseDir)
                .toAbsolutePath()
                .normalize()
                .resolve("user-" + user.getId())
                .normalize();
        try {
            Files.createDirectories(root);
            return root;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to prepare storage.", e);
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
