package com.fbscraper.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fbscraper.model.x.XSyncResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Service
public class XDataExportService {

    private final Path outputDir;
    private final ObjectMapper objectMapper;

    public XDataExportService() {
        this(Path.of("output", "x"));
    }

    XDataExportService(Path outputDir) {
        this.outputDir = outputDir;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void export(XSyncResult result) {
        if (result == null) {
            return;
        }
        try {
            Files.createDirectories(outputDir);
            objectMapper.writeValue(outputDir.resolve("sync-result.json").toFile(), result);
            objectMapper.writeValue(outputDir.resolve("replies.json").toFile(), result.comments());
            objectMapper.writeValue(outputDir.resolve("post-engagement.json").toFile(), result.postReactions());
            System.out.println("[XDataExportService] Exported X sync data to: " + outputDir.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[XDataExportService] Failed to export X sync data: " + e.getMessage());
        }
    }

    public Optional<XSyncResult> loadLatest() {
        Path file = outputDir.resolve("sync-result.json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), XSyncResult.class));
        } catch (IOException e) {
            System.err.println("[XDataExportService] Could not load previous X result: " + e.getMessage());
            return Optional.empty();
        }
    }
}
