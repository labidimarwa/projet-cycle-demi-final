package com.nexgenai.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates uploaded files against OWASP A08 — Software and Data Integrity.
 * Checks: magic bytes, extension whitelist, filename sanitization, max size.
 */
@Component
@RequiredArgsConstructor
public class FileSecurityValidator {

    private final SecurityEventLogger eventLogger;

    private static final long   MAX_SIZE_BYTES    = 10L * 1024 * 1024; // 10 MB
    private static final Set<String> ALLOWED_EXT  = Set.of(
        "pdf", "doc", "docx", "png", "jpg", "jpeg", "gif", "txt", "csv", "xlsx"
    );

    // Dangerous extensions that must never be uploaded
    private static final Set<String> BLOCKED_EXT  = Set.of(
        "exe", "bat", "sh", "cmd", "ps1", "vbs", "js", "jar", "war",
        "php", "py", "rb", "pl", "asp", "aspx", "jsp", "cgi", "htaccess"
    );

    // Path-traversal characters in filenames
    private static final Pattern UNSAFE_FILENAME   = Pattern.compile("[/\\\\:*?\"<>|]|\\.{2}");

    // Known magic bytes: key = mime category, value = first N bytes
    private static final byte[] PDF_MAGIC    = {0x25, 0x50, 0x44, 0x46};           // %PDF
    private static final byte[] PNG_MAGIC    = {(byte)0x89, 0x50, 0x4E, 0x47};     // .PNG
    private static final byte[] JPEG_MAGIC   = {(byte)0xFF, (byte)0xD8, (byte)0xFF};
    private static final byte[] GIF_MAGIC    = {0x47, 0x49, 0x46, 0x38};           // GIF8
    private static final byte[] ZIP_MAGIC    = {0x50, 0x4B, 0x03, 0x04};           // PK.. (docx/xlsx)

    public record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok()           { return new ValidationResult(true,  null); }
        static ValidationResult fail(String r) { return new ValidationResult(false, r);   }
    }

    /**
     * Full validation pipeline. Returns a result; caller decides whether to reject.
     * Call {@code eventLogger.maliciousFileUpload()} on failure.
     */
    public ValidationResult validate(MultipartFile file, String clientIp) {

        if (file == null || file.isEmpty()) return ValidationResult.fail("Empty file");

        // 1. Size check
        if (file.getSize() > MAX_SIZE_BYTES) {
            String reason = "File too large: " + file.getSize() + " bytes";
            eventLogger.maliciousFileUpload(clientIp, file.getOriginalFilename(), reason);
            return ValidationResult.fail(reason);
        }

        // 2. Filename sanitization
        String originalName = file.getOriginalFilename();
        if (originalName == null || UNSAFE_FILENAME.matcher(originalName).find()) {
            String reason = "Unsafe filename: " + originalName;
            eventLogger.maliciousFileUpload(clientIp, originalName, reason);
            return ValidationResult.fail(reason);
        }

        // 3. Extension check
        int dot = originalName.lastIndexOf('.');
        if (dot < 0) {
            eventLogger.maliciousFileUpload(clientIp, originalName, "No extension");
            return ValidationResult.fail("No file extension");
        }
        String ext = originalName.substring(dot + 1).toLowerCase();

        if (BLOCKED_EXT.contains(ext)) {
            String reason = "Blocked extension: " + ext;
            eventLogger.maliciousFileUpload(clientIp, originalName, reason);
            return ValidationResult.fail(reason);
        }

        if (!ALLOWED_EXT.contains(ext)) {
            String reason = "Extension not allowed: " + ext;
            eventLogger.maliciousFileUpload(clientIp, originalName, reason);
            return ValidationResult.fail(reason);
        }

        // 4. Magic bytes (content-type spoofing)
        try (InputStream is = file.getInputStream()) {
            byte[] header = is.readNBytes(8);
            if (!isContentConsistent(ext, header)) {
                String reason = "Magic bytes mismatch for extension: " + ext;
                eventLogger.maliciousFileUpload(clientIp, originalName, reason);
                return ValidationResult.fail(reason);
            }
        } catch (IOException e) {
            return ValidationResult.fail("Cannot read file content");
        }

        return ValidationResult.ok();
    }

    private boolean isContentConsistent(String ext, byte[] header) {
        return switch (ext) {
            case "pdf"              -> startsWith(header, PDF_MAGIC);
            case "png"              -> startsWith(header, PNG_MAGIC);
            case "jpg", "jpeg"      -> startsWith(header, JPEG_MAGIC);
            case "gif"              -> startsWith(header, GIF_MAGIC);
            case "docx", "xlsx"     -> startsWith(header, ZIP_MAGIC); // OOXML is a zip
            // txt/csv/doc: no reliable magic bytes, skip binary check
            default                 -> true;
        };
    }

    private boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) return false;
        return Arrays.equals(Arrays.copyOf(data, magic.length), magic);
    }
}
