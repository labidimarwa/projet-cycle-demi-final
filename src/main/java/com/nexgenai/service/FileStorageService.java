package com.nexgenai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

@Service
public class FileStorageService {

    @Value("${app.cv.upload-dir:uploads/cv}")
    private String uploadDir;

    /**
     * Validates, stores the file, and returns a compound path string
     * in the format {@code "originalName|storedFileName"}.
     */
    public String saveFile(String ownerIdentifier, MultipartFile file) {
        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "cv.pdf";
        String ext = originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf(".")).toLowerCase() : ".pdf";
        if (!List.of(".pdf", ".doc", ".docx").contains(ext))
            throw new IllegalArgumentException("Unsupported format.");
        if (file.getSize() > 5 * 1024 * 1024)
            throw new IllegalArgumentException("File too large (max 5 MB).");
        try {
            Path dir = Paths.get(uploadDir).toAbsolutePath();
            Files.createDirectories(dir);
            String safeId   = ownerIdentifier.replaceAll("[^a-zA-Z0-9._-]", "_");
            String fileName = safeId + ext;
            Files.copy(file.getInputStream(), dir.resolve(fileName),
                    StandardCopyOption.REPLACE_EXISTING);
            return originalName + "|" + fileName;
        } catch (IOException e) {
            throw new IllegalStateException("File upload error: " + e.getMessage(), e);
        }
    }

    /** Resolves the on-disk {@link Path} from a compound storage string. */
    public Path getFilePath(String storagePath) {
        String[] parts = storagePath.split("\\|");
        String fileName = parts.length > 1 ? parts[1] : parts[0];
        return Paths.get(uploadDir).toAbsolutePath().resolve(fileName);
    }

    /** Returns the original display name from a compound storage string. */
    public String getDisplayName(String storagePath) {
        if (storagePath == null) return "cv.pdf";
        return storagePath.split("\\|")[0];
    }
}
