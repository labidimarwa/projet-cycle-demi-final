package com.nexgenai.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileSecurityValidator — unit tests (OWASP A08)")
class FileSecurityValidatorTest {

    @Mock  private SecurityEventLogger eventLogger;
    @InjectMocks private FileSecurityValidator validator;

    private static final String IP = "127.0.0.1";

    // ── Valid files ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid PDF with correct magic bytes → OK")
    void validPdf_returnsOk() {
        byte[] pdfBytes = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34}; // %PDF-1.4
        MockMultipartFile file = new MockMultipartFile(
            "file", "resume.pdf", "application/pdf", pdfBytes);

        var result = validator.validate(file, IP);

        assertTrue(result.valid());
        verifyNoInteractions(eventLogger);
    }

    @Test
    @DisplayName("Valid PNG with correct magic bytes → OK")
    void validPng_returnsOk() {
        byte[] pngBytes = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        MockMultipartFile file = new MockMultipartFile(
            "file", "photo.png", "image/png", pngBytes);

        var result = validator.validate(file, IP);

        assertTrue(result.valid());
        verifyNoInteractions(eventLogger);
    }

    @Test
    @DisplayName("Valid JPEG with correct magic bytes → OK")
    void validJpeg_returnsOk() {
        byte[] jpegBytes = new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0, 0x00, 0x10, 0x4A, 0x46};
        MockMultipartFile file = new MockMultipartFile(
            "file", "photo.jpg", "image/jpeg", jpegBytes);

        var result = validator.validate(file, IP);

        assertTrue(result.valid());
        verifyNoInteractions(eventLogger);
    }

    // ── Dangerous extensions ──────────────────────────────────────────────────

    @ParameterizedTest(name = "Blocked extension: {0}")
    @ValueSource(strings = {
        "malware.exe", "script.bat", "shell.sh", "backdoor.php",
        "trojan.py", "payload.jsp", "hack.cmd", "virus.ps1",
        "webshell.aspx", "exploit.jar"
    })
    @DisplayName("Dangerous file extension → rejected + maliciousFileUpload logged")
    void dangerousExtension_rejected(String filename) {
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/octet-stream",
            "fake content".getBytes());

        var result = validator.validate(file, IP);

        assertFalse(result.valid());
        assertNotNull(result.reason());
        verify(eventLogger, atLeastOnce())
            .maliciousFileUpload(eq(IP), eq(filename), anyString());
    }

    // ── File too large ────────────────────────────────────────────────────────

    @Test
    @DisplayName("File exceeding 10 MB → rejected + logged")
    void fileTooLarge_rejected() {
        byte[] bigContent = new byte[11 * 1024 * 1024]; // 11 MB
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", bigContent);

        var result = validator.validate(file, IP);

        assertFalse(result.valid());
        assertTrue(result.reason().contains("large"));
        verify(eventLogger).maliciousFileUpload(eq(IP), anyString(), anyString());
    }

    // ── Unsafe filenames ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "Unsafe filename: {0}")
    @ValueSource(strings = {
        "../etc/passwd.txt",
        "..\\windows\\system32\\config.pdf",
        "file/with/slash.pdf",
        "file*with*stars.pdf",
        "file?question.pdf"
    })
    @DisplayName("Path traversal in filename → rejected")
    void unsafeFilename_rejected(String filename) {
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/pdf",
            "%PDF-1.4 fake".getBytes());

        var result = validator.validate(file, IP);

        assertFalse(result.valid());
    }

    // ── Magic byte mismatch (polyglot / content-type spoofing) ────────────────

    @Test
    @DisplayName("PDF extension but ZIP magic bytes → rejected (polyglot attack)")
    void pdfExtension_zipMagicBytes_rejected() {
        byte[] zipBytes = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00}; // PK magic
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", zipBytes);

        var result = validator.validate(file, IP);

        assertFalse(result.valid());
        assertTrue(result.reason().contains("mismatch") || result.reason().contains("Magic"));
        verify(eventLogger).maliciousFileUpload(eq(IP), eq("resume.pdf"), anyString());
    }

    @Test
    @DisplayName("PNG extension but EXE content → rejected")
    void pngExtension_exeContent_rejected() {
        byte[] exeBytes = new byte[]{0x4D, 0x5A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}; // MZ header
        MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", exeBytes);

        var result = validator.validate(file, IP);

        assertFalse(result.valid());
    }

    // ── Empty / null file ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty file → rejected")
    void emptyFile_rejected() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        var result = validator.validate(file, IP);

        assertFalse(result.valid());
    }

    @Test
    @DisplayName("Null file → rejected")
    void nullFile_rejected() {
        var result = validator.validate(null, IP);

        assertFalse(result.valid());
    }

    // ── No extension ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("File without extension → rejected")
    void noExtension_rejected() {
        MockMultipartFile file = new MockMultipartFile("file", "noextension", "application/octet-stream",
            "some content".getBytes());

        var result = validator.validate(file, IP);

        assertFalse(result.valid());
    }
}
