package com.fbscraper.web;

import com.fbscraper.model.facebook.FacebookSyncResult;
import com.fbscraper.model.instagram.InstagramSyncResult;
import com.fbscraper.model.x.XSyncResult;
import com.fbscraper.service.SentimentSyncService;
import com.fbscraper.service.XSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api")
public class SyncApiController {

    private final SentimentSyncService syncService;
    private final XSyncService xSyncService;
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final AtomicReference<FacebookSyncResult> latestFbResult;
    private final AtomicReference<InstagramSyncResult> latestIgResult;
    private final AtomicReference<XSyncResult> latestXResult;

    public SyncApiController(SentimentSyncService syncService) {
        this(syncService, null);
    }

    @Autowired
    public SyncApiController(SentimentSyncService syncService, XSyncService xSyncService) {
        this.syncService = syncService;
        this.xSyncService = xSyncService;
        this.latestFbResult = new AtomicReference<>(syncService.loadPreviousFacebookResult().orElse(null));
        this.latestIgResult = new AtomicReference<>(syncService.loadPreviousInstagramResult().orElse(null));
        this.latestXResult = new AtomicReference<>(
                xSyncService == null ? null : xSyncService.loadPreviousResult().orElse(null)
        );
    }

    @PostMapping("/sync")
    public ResponseEntity<?> sync(
            @RequestParam(value = "platform", defaultValue = "facebook") String platform,
            @RequestParam(value = "username", required = false) String username
    ) {
        if (!syncing.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A synchronization is already running"));
        }

        try {
            String normalized = normalizePlatform(platform);
            return switch (normalized) {
                case "instagram" -> {
                    InstagramSyncResult result = syncService.syncInstagram();
                    latestIgResult.set(result);
                    yield ResponseEntity.ok(result);
                }
                case "x" -> {
                    if (xSyncService == null) {
                        yield ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Map.of("error", "X integration is unavailable"));
                    }
                    XSyncResult result = xSyncService.sync(username);
                    latestXResult.set(result);
                    yield ResponseEntity.ok(result);
                }
                case "facebook" -> {
                    FacebookSyncResult result = syncService.syncFacebook();
                    latestFbResult.set(result);
                    yield ResponseEntity.ok(result);
                }
                default -> ResponseEntity.badRequest()
                        .body(Map.of("error", "Unsupported platform: " + platform));
            };
        } catch (RuntimeException e) {
            String message = e.getMessage();
            String error = (message == null || message.isBlank()) ? "Synchronization failed" : message;
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", error));
        } finally {
            syncing.set(false);
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestParam(value = "platform", defaultValue = "facebook") String platform) {
        String normalized = normalizePlatform(platform);
        Object result = switch (normalized) {
            case "instagram" -> latestIgResult.get();
            case "x" -> latestXResult.get();
            case "facebook" -> latestFbResult.get();
            default -> null;
        };

        if (!normalized.equals("facebook") && !normalized.equals("instagram") && !normalized.equals("x")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unsupported platform: " + platform));
        }

        if (result == null) {
            return ResponseEntity.ok(Map.of(
                    "status", "NOT_SYNCED",
                    "syncing", syncing.get(),
                    "platform", normalized
            ));
        }
        return ResponseEntity.ok(result);
    }

    public Optional<FacebookSyncResult> latestFacebookResult() {
        return Optional.ofNullable(latestFbResult.get());
    }

    public Optional<InstagramSyncResult> latestInstagramResult() {
        return Optional.ofNullable(latestIgResult.get());
    }

    public Optional<XSyncResult> latestXResult() {
        return Optional.ofNullable(latestXResult.get());
    }

    public Optional<FacebookSyncResult> latestResult() {
        return latestFacebookResult();
    }

    private String normalizePlatform(String platform) {
        if (platform == null) {
            return "facebook";
        }
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        return "twitter".equals(normalized) ? "x" : normalized;
    }
}
