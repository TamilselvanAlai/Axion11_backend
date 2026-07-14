package com.axion11.visualops.controller;

import com.axion11.visualops.models.CloudConnection;
import com.axion11.visualops.models.User;
import com.axion11.visualops.service.CloudConnectionService;
import com.axion11.visualops.service.CloudSyncService;
import com.axion11.visualops.service.GoogleDriveService;
import com.axion11.visualops.service.OneDriveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/cloud")
@RequiredArgsConstructor
public class CloudConnectionController {

    private final CloudConnectionService cloudConnectionService;
    private final CloudSyncService cloudSyncService;
    private final GoogleDriveService googleDriveService;
    private final OneDriveService oneDriveService;

    // ---------- OAuth Connection endpoints ----------

    @GetMapping("/connections")
    public ResponseEntity<List<Map<String, Object>>> getConnections(@AuthenticationPrincipal User user) {
        List<CloudConnection> connections = cloudConnectionService.getUserConnections(user);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CloudConnection c : connections) {
            result.add(toDto(c));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{provider}/auth-url")
    public ResponseEntity<Map<String, Object>> getAuthUrl(@PathVariable("provider") String provider,
                                                          @RequestBody(required = false) Map<String, String> requestBody,
                                                          @AuthenticationPrincipal User user) {
        try {
            // Lets the caller (e.g. the web app on whatever origin it's actually running on) receive
            // the callback itself instead of always going to the redirect URI baked into server
            // config — mirrors the same pattern used by /api/auth/google/auth-url for sign-in.
            String origin = requestBody != null ? requestBody.get("origin") : null;
            String customRedirectUri = (origin != null && !origin.isEmpty())
                    ? origin + "/oauth/callback/" + provider
                    : null;
            String state = "u" + user.getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
            String authUrl = cloudConnectionService.buildAuthUrl(provider, state, customRedirectUri);
            return ResponseEntity.ok(Map.of("authUrl", authUrl, "state", state, "configured", true));
        } catch (IllegalStateException e) {
            return ResponseEntity.ok(Map.of("configured", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to build auth URL: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Used by the desktop app's native Drive-connect flow: it authenticates directly with
     * Google itself (its own "Desktop app" OAuth client) and hands us the resulting tokens to
     * store, instead of a code for us to exchange (see {@link #handleCallback}).
     */
    @PostMapping("/{provider}/register-native")
    public ResponseEntity<Map<String, Object>> registerNative(@PathVariable("provider") String provider,
                                                               @RequestBody Map<String, Object> body,
                                                               @AuthenticationPrincipal User user) {
        String accessToken = (String) body.get("accessToken");
        String refreshToken = (String) body.get("refreshToken");
        Object expiresInRaw = body.get("expiresInSeconds");
        if (accessToken == null || accessToken.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing accessToken"));
        }
        long expiresIn = expiresInRaw != null ? ((Number) expiresInRaw).longValue() : 3600;
        try {
            CloudConnection conn = cloudConnectionService.registerNativeConnection(user, provider, accessToken, refreshToken, expiresIn);
            return ResponseEntity.ok(toDto(conn));
        } catch (Exception e) {
            log.error("Native connection registration failed for {}: {}", provider, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{provider}/callback")
    public ResponseEntity<Map<String, Object>> handleCallback(@PathVariable("provider") String provider,
                                                              @RequestBody Map<String, String> body,
                                                              @AuthenticationPrincipal User user) {
        String code = body.get("code");
        if (code == null || code.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing authorization code"));
        }
        String redirectUri = body.get("redirectUri");
        try {
            CloudConnection conn = cloudConnectionService.handleOAuthCallback(user, provider, code, redirectUri);
            return ResponseEntity.ok(toDto(conn));
        } catch (Exception e) {
            log.error("OAuth callback failed for {}: {}", provider, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{provider}/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@PathVariable("provider") String provider,
                                                       @AuthenticationPrincipal User user) {
        return cloudConnectionService.findConnection(user, provider)
                .map(c -> ResponseEntity.ok(toDto(cloudConnectionService.refreshConnectionData(c.getId()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{provider}/sync")
    public ResponseEntity<Map<String, Object>> sync(@PathVariable("provider") String provider,
                                                    @RequestBody Map<String, Object> body,
                                                    @AuthenticationPrincipal User user) {
        Object batchIdRaw = body.get("batchId");
        if (batchIdRaw == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Missing batchId");
            return ResponseEntity.badRequest().body(err);
        }
        Long batchId;
        try {
            batchId = Long.parseLong(batchIdRaw.toString().replaceFirst("^b-", ""));
        } catch (NumberFormatException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Invalid batchId");
            return ResponseEntity.badRequest().body(err);
        }
        Object rootFolderRaw = body.get("rootFolderPath");
        String rootFolderPath = rootFolderRaw == null ? null : rootFolderRaw.toString().trim();
        return cloudConnectionService.findConnection(user, provider)
                .map(c -> {
                    try {
                        CloudSyncService.SyncResult r = cloudSyncService.sync(c.getId(), batchId, rootFolderPath);
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("connection", toDto(cloudConnectionService.findConnection(user, provider).orElse(c)));
                        out.put("result", r.toMap());
                        return ResponseEntity.ok(out);
                    } catch (Exception e) {
                        log.error("Sync failed: {}", e.getMessage(), e);
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("error", e.getMessage() == null ? "sync error" : e.getMessage());
                        return ResponseEntity.<Map<String, Object>>internalServerError().body(err);
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Google Picker (drive.file scope) renders client-side — it needs a short-lived access
    // token plus the picker API key (a separate, non-secret credential from the OAuth client).
    @GetMapping("/google/picker-token")
    public ResponseEntity<Map<String, Object>> getPickerToken(@AuthenticationPrincipal User user) {
        return cloudConnectionService.findConnection(user, CloudConnectionService.PROVIDER_GOOGLE_DRIVE)
                .map(c -> {
                    try {
                        String accessToken = cloudConnectionService.getValidAccessToken(c);
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("accessToken", accessToken);
                        out.put("apiKey", googleDriveService.getPickerApiKey());
                        return ResponseEntity.ok(out);
                    } catch (Exception e) {
                        log.error("Failed to get picker token: {}", e.getMessage());
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("error", e.getMessage() == null ? "token error" : e.getMessage());
                        return ResponseEntity.<Map<String, Object>>internalServerError().body(err);
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{provider}/browse")
    public ResponseEntity<Map<String, Object>> browse(@PathVariable("provider") String provider,
                                                       @RequestParam(value = "folderId", required = false) String folderId,
                                                       @AuthenticationPrincipal User user) {
        return cloudConnectionService.findConnection(user, provider)
                .map(c -> {
                    try {
                        String accessToken = cloudConnectionService.getValidAccessToken(c);
                        boolean isGoogle = CloudConnectionService.PROVIDER_GOOGLE_DRIVE.equals(c.getProvider());
                        List<Map<String, Object>> items = new ArrayList<>();
                        if (isGoogle) {
                            for (GoogleDriveService.DriveEntry e : googleDriveService.listChildren(accessToken, folderId)) {
                                items.add(entryDto(e.id, e.name, e.isFolder, e.sizeBytes));
                            }
                        } else {
                            for (OneDriveService.DriveEntry e : oneDriveService.listChildren(accessToken, folderId)) {
                                items.add(entryDto(e.id, e.name, e.isFolder, e.sizeBytes));
                            }
                        }
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("folderId", folderId == null ? "root" : folderId);
                        out.put("items", items);
                        return ResponseEntity.ok(out);
                    } catch (Exception e) {
                        log.error("Browse failed: {}", e.getMessage(), e);
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("error", e.getMessage() == null ? "browse error" : e.getMessage());
                        return ResponseEntity.<Map<String, Object>>internalServerError().body(err);
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/{provider}/import")
    public ResponseEntity<Map<String, Object>> importSelection(@PathVariable("provider") String provider,
                                                                @RequestBody Map<String, Object> body,
                                                                @AuthenticationPrincipal User user) {
        Object batchIdRaw = body.get("batchId");
        Object itemsRaw = body.get("items");
        if (batchIdRaw == null || !(itemsRaw instanceof List)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Missing batchId or items");
            return ResponseEntity.badRequest().body(err);
        }
        Long batchId;
        try {
            batchId = Long.parseLong(batchIdRaw.toString().replaceFirst("^b-", ""));
        } catch (NumberFormatException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Invalid batchId");
            return ResponseEntity.badRequest().body(err);
        }
        List<CloudSyncService.ImportItem> items = new ArrayList<>();
        for (Object raw : (List<Object>) itemsRaw) {
            if (!(raw instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) raw;
            items.add(new CloudSyncService.ImportItem(
                    String.valueOf(m.get("id")),
                    String.valueOf(m.get("name")),
                    Boolean.TRUE.equals(m.get("isFolder"))));
        }

        Long finalBatchId = batchId;
        return cloudConnectionService.findConnection(user, provider)
                .map(c -> {
                    try {
                        CloudSyncService.SyncResult r = cloudSyncService.importItems(c.getId(), finalBatchId, items);
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("result", r.toMap());
                        return ResponseEntity.ok(out);
                    } catch (Exception e) {
                        log.error("Import failed: {}", e.getMessage(), e);
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("error", e.getMessage() == null ? "import error" : e.getMessage());
                        return ResponseEntity.<Map<String, Object>>internalServerError().body(err);
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> entryDto(String id, String name, boolean isFolder, Long sizeBytes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("isFolder", isFolder);
        m.put("size", sizeBytes);
        return m;
    }

    @DeleteMapping("/{provider}/disconnect")
    public ResponseEntity<Map<String, String>> disconnect(@PathVariable("provider") String provider,
                                                          @AuthenticationPrincipal User user) {
        cloudConnectionService.disconnect(user.getId(), provider);
        return ResponseEntity.ok(Map.of("status", "disconnected"));
    }

    private Map<String, Object> toDto(CloudConnection c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("provider", c.getProvider());
        m.put("status", c.getStatus());
        m.put("storageUsedBytes", c.getStorageUsedBytes());
        m.put("totalStorageBytes", c.getTotalStorageBytes());
        m.put("fileCount", c.getTotalFileCount());
        m.put("lastSyncedAt", toEpochMilli(c.getLastSyncedAt()));
        m.put("connectedAt", toEpochMilli(c.getConnectedAt()));
        return m;
    }

    private Long toEpochMilli(LocalDateTime dt) {
        return dt == null ? null : dt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    // ---------- Legacy FTP endpoints ----------

    @PostMapping("/ftp/connect")
    public ResponseEntity<Map<String, Object>> ftpConnect(@RequestBody Map<String, String> body) {
        String host = body.getOrDefault("host", "");
        int port = Integer.parseInt(body.getOrDefault("port", "21"));
        String username = body.getOrDefault("username", "anonymous");
        String password = body.getOrDefault("password", "");
        String path = body.getOrDefault("path", "/");

        try {
            org.apache.commons.net.ftp.FTPClient ftp = new org.apache.commons.net.ftp.FTPClient();
            ftp.connect(host, port);
            ftp.login(username, password);
            ftp.enterLocalPassiveMode();
            ftp.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE);

            org.apache.commons.net.ftp.FTPFile[] files = ftp.listFiles(path);

            List<Map<String, Object>> fileList = new ArrayList<>();
            for (org.apache.commons.net.ftp.FTPFile f : files) {
                if (f.getName().equals(".") || f.getName().equals("..")) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", f.getName());
                entry.put("path", path.endsWith("/") ? path + f.getName() : path + "/" + f.getName());
                entry.put("isDirectory", f.isDirectory());
                entry.put("size", f.getSize());
                fileList.add(entry);
            }

            ftp.disconnect();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", true);
            result.put("files", fileList);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("FTP connection failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("connected", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/ftp/download")
    public ResponseEntity<byte[]> ftpDownload(@RequestBody Map<String, String> body) {
        String host = body.getOrDefault("host", "");
        int port = Integer.parseInt(body.getOrDefault("port", "21"));
        String username = body.getOrDefault("username", "anonymous");
        String password = body.getOrDefault("password", "");
        String filePath = body.getOrDefault("filePath", "");

        try {
            org.apache.commons.net.ftp.FTPClient ftp = new org.apache.commons.net.ftp.FTPClient();
            ftp.connect(host, port);
            ftp.login(username, password);
            ftp.enterLocalPassiveMode();
            ftp.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            boolean success = ftp.retrieveFile(filePath, bos);
            ftp.disconnect();

            if (!success) {
                return ResponseEntity.badRequest().build();
            }

            String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                    .header("X-File-Name", fileName)
                    .body(bos.toByteArray());
        } catch (Exception e) {
            log.error("FTP download failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
