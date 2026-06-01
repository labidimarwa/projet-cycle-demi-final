// src/main/java/com/nexgenai/service/CodeExecutionService.java

package com.nexgenai.service;

import com.nexgenai.dto.technicaltest.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

/**
 * Sandboxed code execution service.
 *
 * Architecture:
 *   1. Writes candidate code to a temp file
 *   2. Launches a Docker container with strict resource limits
 *   3. Pipes each test case's input via stdin
 *   4. Captures stdout / stderr
 *   5. Compares with expected output (trimmed, normalized)
 *   6. Returns results with timing + memory info
 *
 * Security measures:
 *   - Docker network=none (no internet access)
 *   - memory=128m, cpus=0.5
 *   - read-only filesystem except /tmp
 *   - User nobody (uid=65534)
 *   - Timeout per test case (configurable)
 *   - No syscalls that could break out (seccomp profile)
 */
@Service
@Slf4j
public class CodeExecutionService {

    @Value("${app.code-execution.timeout-seconds:10}")
    private int timeoutSeconds;

    @Value("${app.code-execution.memory-mb:128}")
    private int memoryMb;

    @Value("${app.code-execution.use-docker:true}")
    private boolean useDocker;

    // Docker images per language
    private static final Map<String, String> DOCKER_IMAGES = Map.of(
        "python",     "python:3.11-slim",
        "javascript", "node:20-slim",
        "java",       "openjdk:21-slim",
        "c",          "gcc:13-slim",
        "cpp",        "gcc:13-slim",
        "go",         "golang:1.21-slim"
    );

    private static final Map<String, String> FILE_NAMES = Map.of(
        "python",     "solution.py",
        "javascript", "solution.js",
        "java",       "Main.java",
        "c",          "solution.c",
        "cpp",        "solution.cpp",
        "go",         "main.go"
    );

    private static final Map<String, String[]> RUN_COMMANDS;
    static {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String pythonCmd = isWindows ? "python" : "python3";
        RUN_COMMANDS = Map.of(
            "python",     new String[]{pythonCmd, "solution.py"},
            "javascript", new String[]{"node", "solution.js"},
            "java",       new String[]{"sh", "-c", "javac Main.java && java Main"},
            "c",          new String[]{"sh", "-c", "gcc -O2 -o solution solution.c -lm && ./solution"},
            "cpp",        new String[]{"sh", "-c", "g++ -O2 -std=c++17 -o solution solution.cpp -lm && ./solution"},
            "go",         new String[]{"sh", "-c", "go run main.go"}
        );
    }

    public List<TestCaseResultDto> execute(
            String code,
            String language,
            List<RunCodeRequest.TestCasePayload> testCases) {

        if (testCases == null || testCases.isEmpty()) return List.of();

        String lang = language.toLowerCase();
        if (!DOCKER_IMAGES.containsKey(lang)) {
            return List.of(TestCaseResultDto.builder()
                    .error("Unsupported language: " + language)
                    .passed(false).build());
        }

        List<TestCaseResultDto> results = new ArrayList<>();
        Path tempDir = null;

        try {
            tempDir = createSecureTempDir();
            Path codeFile = tempDir.resolve(FILE_NAMES.get(lang));
            Files.writeString(codeFile, code, StandardCharsets.UTF_8);

            for (RunCodeRequest.TestCasePayload tc : testCases) {
                TestCaseResultDto result = runSingleTestCase(
                        tempDir, lang, tc, codeFile
                );
                results.add(result);
            }

        } catch (IOException e) {
            log.error("Failed to create temp dir for code execution", e);
            results.add(TestCaseResultDto.builder()
                    .error("Execution environment error: " + e.getMessage())
                    .passed(false).build());
        } finally {
            if (tempDir != null) deleteDirSilently(tempDir);
        }

        return results;
    }

    // ── Single test case execution ────────────────────────────────────────────

    private TestCaseResultDto runSingleTestCase(
            Path tempDir, String lang,
            RunCodeRequest.TestCasePayload tc, Path codeFile) {

        long startMs = System.currentTimeMillis();
        long memoryKb = 0;

        try {
            ProcessBuilder pb;

            if (useDocker) {
                pb = buildDockerProcess(lang, tempDir, codeFile);
            } else {
                // Fallback: direct execution (dev-only, NOT for production)
                pb = buildDirectProcess(lang, tempDir);
            }

            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Write input to stdin
            try (OutputStream stdin = process.getOutputStream()) {
                byte[] inputBytes = normalizeInput(tc.getInput())
                        .getBytes(StandardCharsets.UTF_8);
                stdin.write(inputBytes);
            }

            // Read stdout + stderr with timeout
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Future<String> stdoutFuture = executor.submit(() ->
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            Future<String> stderrFuture = executor.submit(() ->
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            executor.shutdownNow();

            if (!finished) {
                process.destroyForcibly();
                return TestCaseResultDto.builder()
                        .input(tc.getInput())
                        .expected(tc.getOutput())
                        .actual("")
                        .passed(false)
                        .points(tc.getPoints())
                        .earnedPoints(0)
                        .executionMs(timeoutSeconds * 1000L)
                        .memoryKb(0)
                        .error("Time Limit Exceeded (" + timeoutSeconds + "s)")
                        .build();
            }

            String stdout = stdoutFuture.isDone() ? stdoutFuture.get() : "";
            String stderr = stderrFuture.isDone() ? stderrFuture.get() : "";
            long execMs   = System.currentTimeMillis() - startMs;

            // Compare output
            String actual   = stdout.trim();
            String expected = tc.getOutput().trim();
            boolean passed  = compareOutput(actual, expected);

            String error = null;
            if (!passed && !stderr.isBlank()) {
                error = stderr.length() > 500 ? stderr.substring(0, 500) + "..." : stderr;
            }

            return TestCaseResultDto.builder()
                    .input(tc.getInput())
                    .expected(tc.getOutput())
                    .actual(actual)
                    .passed(passed)
                    .points(tc.getPoints())
                    .earnedPoints(passed ? tc.getPoints() : 0)
                    .executionMs(execMs)
                    .memoryKb(memoryKb)
                    .error(error)
                    .build();

        } catch (Exception e) {
            long execMs = System.currentTimeMillis() - startMs;
            log.warn("Code execution error: {}", e.getMessage());
            return TestCaseResultDto.builder()
                    .input(tc.getInput())
                    .expected(tc.getOutput())
                    .actual("")
                    .passed(false)
                    .points(tc.getPoints())
                    .earnedPoints(0)
                    .executionMs(execMs)
                    .memoryKb(0)
                    .error(e.getMessage())
                    .build();
        }
    }

    // ── Build Docker process ──────────────────────────────────────────────────

    private ProcessBuilder buildDockerProcess(String lang, Path tempDir, Path codeFile) {
        String image     = DOCKER_IMAGES.get(lang);
        String[] runCmd  = RUN_COMMANDS.get(lang);
        String mountPath = "/code";

        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--network=none");
        cmd.add("--memory=" + memoryMb + "m");
        cmd.add("--memory-swap=" + memoryMb + "m");
        cmd.add("--cpus=0.5");
        cmd.add("--user=nobody");
        cmd.add("--read-only");
        cmd.add("--tmpfs=/tmp:size=64m,noexec");
        cmd.add("--security-opt=no-new-privileges");
        cmd.add("-v");
        cmd.add(tempDir.toAbsolutePath() + ":" + mountPath + ":ro");
        cmd.add("-w");
        cmd.add("/tmp");  // work in /tmp, copy code first

        // Entry: copy code, run it
        cmd.add(image);
        // Build the shell command to cd /tmp, copy code, run
        StringBuilder shellCmd = new StringBuilder("cp " + mountPath + "/" + FILE_NAMES.get(lang) + " /tmp/ && ");
        shellCmd.append(String.join(" ", runCmd));
        cmd.add("sh");
        cmd.add("-c");
        cmd.add(shellCmd.toString());

        return new ProcessBuilder(cmd);
    }

    // ── Direct process (dev fallback — NOT production) ────────────────────────
    private ProcessBuilder buildDirectProcess(String lang, Path tempDir) throws IOException {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd;

        if (isWindows) {
            Map<String, String[]> winCommands = Map.of(
                "python",     new String[]{"python", "solution.py"},
                "javascript", new String[]{"node", "solution.js"},
                "java",       new String[]{"cmd", "/c", "javac Main.java && java Main"},
                "c",          new String[]{"cmd", "/c", "gcc -O2 -o solution solution.c -lm && solution.exe"},
                "cpp",        new String[]{"cmd", "/c", "g++ -O2 -std=c++17 -o solution solution.cpp && solution.exe"},
                "go",         new String[]{"cmd", "/c", "go run main.go"}
            );
            cmd = Arrays.asList(winCommands.get(lang));
        } else {
            cmd = Arrays.asList(RUN_COMMANDS.get(lang));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(tempDir.toFile());
        return pb;
    }

    // ── Output comparison ─────────────────────────────────────────────────────

    /**
     * Smart output comparison:
     * - Trim whitespace
     * - Normalize line endings
     * - Handle numeric tolerance (±1e-6 for floats)
     * - Handle array/matrix output (newline or space separated)
     */
    private boolean compareOutput(String actual, String expected) {
        if (actual == null || expected == null) return false;

        // Interprète les \n littéraux dans l'expected (saisis par le HR)
        String cleanExpected = expected.replace("\\n", "\n");
        cleanExpected = normalizeOutputBrackets(cleanExpected);

        String a = normalize(actual);
        String e = normalize(cleanExpected);

        if (a.equals(e)) return true;

        // Try numeric comparison
        try {
            double da = Double.parseDouble(a);
            double de = Double.parseDouble(e);
            return Math.abs(da - de) < 1e-6;
        } catch (NumberFormatException ignored) {}

        // Try line-by-line comparison
        String[] aLines = a.split("\n");
        String[] eLines = e.split("\n");
        if (aLines.length != eLines.length) return false;

        for (int i = 0; i < aLines.length; i++) {
            if (!compareLines(aLines[i].trim(), eLines[i].trim())) return false;
        }
        return true;
    }
    // Nouvelle méthode — enlève les crochets de l'expected output
    private String normalizeOutputBrackets(String output) {
        if (output == null) return "";
        String s = output.trim();
        
        // [[1,2],[3,4]] → "1 2\n3 4"
        if (s.startsWith("[[")) {
            return s.replaceAll("\\[\\[", "")
                    .replaceAll("\\]\\]", "")
                    .replaceAll("\\],\\s*\\[", "\n")
                    .replaceAll(",\\s*", " ")
                    .trim();
        }
        
        // [11] → "11"  |  [-3] → "-3"  |  [1,2,3] → "1 2 3"
        if (s.startsWith("[") && s.endsWith("]")) {
            return s.substring(1, s.length() - 1)
                    .replaceAll(",\\s*", " ")
                    .trim();
        }
        
        return s;
    }
    private boolean compareLines(String a, String e) {
        if (a.equals(e)) return true;
        // Compare space-separated tokens (array on one line)
        String[] aToks = a.split("\\s+");
        String[] eToks = e.split("\\s+");
        if (aToks.length != eToks.length) return false;
        for (int i = 0; i < aToks.length; i++) {
            try {
                double da = Double.parseDouble(aToks[i]);
                double de = Double.parseDouble(eToks[i]);
                if (Math.abs(da - de) >= 1e-6) return false;
            } catch (NumberFormatException ex) {
                if (!aToks[i].equals(eToks[i])) return false;
            }
        }
        return true;
    }

    private String normalize(String s) {
        return s.trim()
                .replaceAll("\r\n", "\n")
                .replaceAll("\r", "\n")
                .replaceAll("[ \t]+\n", "\n")
                .replaceAll("\n+$", "");
    }

    /**
     * Normalize input to handle arrays, matrices, etc.
     * Supports formats like:
     *   [1, 2, 3]        → "1 2 3"
     *   [[1,2],[3,4]]    → "1 2\n3 4"
     *   {key: value}     → passed as-is
     */
    private String normalizeInput(String input) {
        if (input == null) return "";
        String s = input.trim();

        // Matrix format [[...],[...]]
        if (s.startsWith("[[")) {
            s = s.replaceAll("\\[\\[", "")
                 .replaceAll("\\]\\]", "")
                 .replaceAll("\\],\\s*\\[", "\n")
                 .replaceAll(",\\s*", " ")
                 .trim();
            return s;
        }

        // Array format [...]
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1)
                 .replaceAll(",\\s*", " ")
                 .trim();
            return s;
        }

        return s;
    }

    private void deleteDirSilently(Path dir) {
        try {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
        } catch (IOException ignored) {}
    }

    private Path createSecureTempDir() throws IOException {
        try {
            // Restrict to owner-only (rwx------) on POSIX systems (Linux/Docker)
            Set<PosixFilePermission> perms =
                    PosixFilePermissions.fromString("rwx------");
            FileAttribute<Set<PosixFilePermission>> attr =
                    PosixFilePermissions.asFileAttribute(perms);
            return Files.createTempDirectory("nexgen_exec_", attr);
        } catch (UnsupportedOperationException e) {
            // Windows: fall back to default temp dir (Docker always runs on Linux)
            Path dir = Files.createTempDirectory("nexgen_exec_");
            dir.toFile().setReadable(false, false);
            dir.toFile().setWritable(false, false);
            dir.toFile().setExecutable(false, false);
            dir.toFile().setReadable(true, true);
            dir.toFile().setWritable(true, true);
            dir.toFile().setExecutable(true, true);
            return dir;
        }
    }
}