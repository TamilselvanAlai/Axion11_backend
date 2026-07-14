package com.axion11.visualops.service;

import com.axion11.visualops.models.CloudConnection;
import com.axion11.visualops.models.User;
import com.axion11.visualops.repository.CloudConnectionRepository;
import com.axion11.visualops.repository.SyncedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudConnectionService {

    public static final String PROVIDER_GOOGLE_DRIVE = "GOOGLE_DRIVE";
    public static final String PROVIDER_ONEDRIVE = "ONEDRIVE";

    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_DISCONNECTED = "DISCONNECTED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private final CloudConnectionRepository connectionRepository;
    private final SyncedFileRepository syncedFileRepository;
    private final TokenEncryptionService encryptionService;
    private final GoogleDriveService googleDriveService;
    private final OneDriveService oneDriveService;

    public List<CloudConnection> getUserConnections(User user) {
        // Each user only ever sees their own connections — never another user's,
        // regardless of role. (Previously admins with no connection of their own
        // fell back to whichever Google Drive connection existed first in the
        // whole database, leaking another user's Drive contents.)
        return connectionRepository.findByUserId(user.getId());
    }

    public Optional<CloudConnection> findConnection(User user, String provider) {
        String normProvider = normalizeProvider(provider);
        return connectionRepository.findByUserIdAndProvider(user.getId(), normProvider);
    }

    /** Creates or updates a connection from an OAuth callback. */
    public CloudConnection handleOAuthCallback(User user, String provider, String authCode) {
        return handleOAuthCallback(user, provider, authCode, null);
    }

    /** @param customRedirectUri must match exactly what was passed to {@link #buildAuthUrl}. */
    public CloudConnection handleOAuthCallback(User user, String provider, String authCode, String customRedirectUri) {
        String normProvider = normalizeProvider(provider);

        String accessToken;
        String refreshToken;
        long expiresIn;

        if (PROVIDER_GOOGLE_DRIVE.equals(normProvider)) {
            GoogleDriveService.TokenResponse tokens = googleDriveService.exchangeCodeForTokens(authCode, customRedirectUri);
            accessToken = tokens.accessToken;
            refreshToken = tokens.refreshToken;
            expiresIn = tokens.expiresInSeconds;
        } else if (PROVIDER_ONEDRIVE.equals(normProvider)) {
            OneDriveService.TokenResponse tokens = oneDriveService.exchangeCodeForTokens(authCode);
            accessToken = tokens.accessToken;
            refreshToken = tokens.refreshToken;
            expiresIn = tokens.expiresInSeconds;
        } else {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }

        return upsertConnection(user, normProvider, accessToken, refreshToken, expiresIn);
    }

    /**
     * Registers a connection from tokens the caller already obtained itself — used by the
     * desktop app's native Drive-connect flow, which authenticates directly with Google (via
     * its own "Desktop app" OAuth client, to support loopback redirects) rather than going
     * through {@link #handleOAuthCallback}'s code exchange. Without this, the desktop app could
     * connect Drive natively but the backend would have no record of it, so browse/import would
     * 404 — there'd be nothing to look up.
     *
     * Note: token refresh ({@link GoogleDriveService#refreshAccessToken}) uses the web client's
     * credentials. A refresh token issued by the desktop client may not be refreshable through
     * that path — acceptable for now since the access token is valid ~1hr from registration,
     * but a future improvement would track which client issued a connection's tokens.
     */
    public CloudConnection registerNativeConnection(User user, String provider, String accessToken,
                                                      String refreshToken, long expiresIn) {
        String normProvider = normalizeProvider(provider);
        if (!PROVIDER_GOOGLE_DRIVE.equals(normProvider)) {
            throw new IllegalArgumentException("Native connection registration only supports Google Drive");
        }
        return upsertConnection(user, normProvider, accessToken, refreshToken, expiresIn);
    }

    private CloudConnection upsertConnection(User user, String normProvider, String accessToken,
                                              String refreshToken, long expiresIn) {
        CloudConnection conn = connectionRepository.findByUserIdAndProvider(user.getId(), normProvider)
                .orElseGet(() -> CloudConnection.builder()
                        .user(user)
                        .provider(normProvider)
                        .connectedAt(LocalDateTime.now())
                        .build());

        conn.setAccessToken(encryptionService.encrypt(accessToken));
        if (refreshToken != null) conn.setRefreshToken(encryptionService.encrypt(refreshToken));
        conn.setTokenExpiry(LocalDateTime.now().plusSeconds(expiresIn));
        conn.setStatus(STATUS_CONNECTED);
        conn = connectionRepository.save(conn);

        // Fetch storage + file count asynchronously so the UI gets a quick response
        refreshConnectionDataAsync(conn.getId());

        return conn;
    }

    @Async
    public void refreshConnectionDataAsync(Long connectionId) {
        try {
            refreshConnectionData(connectionId);
        } catch (Exception e) {
            log.error("Failed async refresh for connection {}: {}", connectionId, e.getMessage());
        }
    }

    /** Synchronously refresh storage + file count for a connection. */
    public CloudConnection refreshConnectionData(Long connectionId) {
        CloudConnection conn = connectionRepository.findById(connectionId).orElseThrow();
        String accessToken = getValidAccessToken(conn);

        try {
            if (PROVIDER_GOOGLE_DRIVE.equals(conn.getProvider())) {
                GoogleDriveService.StorageQuota q = googleDriveService.getStorageQuota(accessToken);
                conn.setStorageUsedBytes(q.usedBytes);
                conn.setTotalStorageBytes(q.totalBytes);
                conn.setTotalFileCount(googleDriveService.getFileCount(accessToken));
            } else if (PROVIDER_ONEDRIVE.equals(conn.getProvider())) {
                OneDriveService.StorageQuota q = oneDriveService.getStorageQuota(accessToken);
                conn.setStorageUsedBytes(q.usedBytes);
                conn.setTotalStorageBytes(q.totalBytes);
                conn.setTotalFileCount(oneDriveService.getFileCount(accessToken));
            }
            conn.setLastSyncedAt(LocalDateTime.now());
            conn.setStatus(STATUS_CONNECTED);
        } catch (Exception e) {
            log.error("Refresh failed for {} connection {}: {}", conn.getProvider(), connectionId, e.getMessage());
            conn.setStatus(STATUS_EXPIRED);
        }
        return connectionRepository.save(conn);
    }

    @org.springframework.transaction.annotation.Transactional
    public void disconnect(Long userId, String provider) {
        String normProvider = normalizeProvider(provider);
        Optional<CloudConnection> connOpt = connectionRepository.findByUserIdAndProvider(userId, normProvider);
        if (connOpt.isEmpty()) return;

        CloudConnection conn = connOpt.get();
        try {
            if (conn.getAccessToken() != null && PROVIDER_GOOGLE_DRIVE.equals(conn.getProvider())) {
                try {
                    googleDriveService.revokeToken(encryptionService.decrypt(conn.getAccessToken()));
                } catch (Exception e) {
                    log.warn("Failed to decrypt access token during disconnect: {}", e.getMessage());
                }
            }
            // OneDrive has no simple revoke endpoint — tokens expire naturally.
        } catch (Exception e) {
            log.warn("Token revoke failed: {}", e.getMessage());
        }
        deleteConnectionCascading(conn);
    }

    /**
     * Deletes a cloud connection along with its dependent synced-file index entries.
     * synced_files.cloud_connection_id is a NOT NULL FK, so it must be cleared first or
     * the delete fails with a foreign key constraint violation.
     */
    @org.springframework.transaction.annotation.Transactional
    public void deleteConnectionCascading(CloudConnection conn) {
        syncedFileRepository.deleteByCloudConnectionId(conn.getId());
        connectionRepository.delete(conn);
    }

    /** Decrypts the access token, refreshing if expired. */
    public String getValidAccessToken(CloudConnection conn) {
        LocalDateTime now = LocalDateTime.now();
        boolean needsRefresh = conn.getTokenExpiry() == null
                || conn.getTokenExpiry().isBefore(now.plusMinutes(5));

        if (!needsRefresh) {
            try {
                return encryptionService.decrypt(conn.getAccessToken());
            } catch (Exception e) {
                log.warn("Decryption of access token failed. Deleting connection {}: {}", conn.getId(), e.getMessage());
                deleteConnectionCascading(conn);
                throw new RuntimeException("Decryption failed - connection reset. Please reconnect.", e);
            }
        }

        if (conn.getRefreshToken() == null) {
            conn.setStatus(STATUS_EXPIRED);
            connectionRepository.save(conn);
            throw new RuntimeException("No refresh token — user must reconnect");
        }

        String refreshTokenPlain;
        try {
            refreshTokenPlain = encryptionService.decrypt(conn.getRefreshToken());
        } catch (Exception e) {
            log.warn("Decryption of refresh token failed. Deleting connection {}: {}", conn.getId(), e.getMessage());
            deleteConnectionCascading(conn);
            throw new RuntimeException("Decryption failed - connection reset. Please reconnect.", e);
        }

        try {
            if (PROVIDER_GOOGLE_DRIVE.equals(conn.getProvider())) {
                GoogleDriveService.TokenResponse t = googleDriveService.refreshAccessToken(refreshTokenPlain);
                conn.setAccessToken(encryptionService.encrypt(t.accessToken));
                conn.setTokenExpiry(now.plusSeconds(t.expiresInSeconds));
                connectionRepository.save(conn);
                return t.accessToken;
            } else if (PROVIDER_ONEDRIVE.equals(conn.getProvider())) {
                OneDriveService.TokenResponse t = oneDriveService.refreshAccessToken(refreshTokenPlain);
                conn.setAccessToken(encryptionService.encrypt(t.accessToken));
                if (t.refreshToken != null) conn.setRefreshToken(encryptionService.encrypt(t.refreshToken));
                conn.setTokenExpiry(now.plusSeconds(t.expiresInSeconds));
                connectionRepository.save(conn);
                return t.accessToken;
            }
        } catch (Exception e) {
            conn.setStatus(STATUS_EXPIRED);
            connectionRepository.save(conn);
            throw new RuntimeException("Token refresh failed: " + e.getMessage());
        }
        throw new IllegalStateException("Unknown provider: " + conn.getProvider());
    }

    public String buildAuthUrl(String provider, String state) {
        return buildAuthUrl(provider, state, null);
    }

    /** @param customRedirectUri must be an exact match for a URI registered with the OAuth client. */
    public String buildAuthUrl(String provider, String state, String customRedirectUri) {
        String normProvider = normalizeProvider(provider);
        if (PROVIDER_GOOGLE_DRIVE.equals(normProvider)) {
            if (!googleDriveService.isConfigured()) {
                throw new IllegalStateException("Google Drive OAuth not configured");
            }
            return googleDriveService.buildAuthorizationUrl(state, customRedirectUri);
        } else if (PROVIDER_ONEDRIVE.equals(normProvider)) {
            if (!oneDriveService.isConfigured()) {
                throw new IllegalStateException("OneDrive OAuth not configured");
            }
            return oneDriveService.buildAuthorizationUrl(state);
        }
        throw new IllegalArgumentException("Unknown provider: " + provider);
    }

    public static String normalizeProvider(String provider) {
        if (provider == null) return "";
        String p = provider.toLowerCase().replace("_", "-");
        if (p.equals("google-drive") || p.equals("googledrive") || p.equals("google")) return PROVIDER_GOOGLE_DRIVE;
        if (p.equals("onedrive")) return PROVIDER_ONEDRIVE;
        return provider.toUpperCase();
    }
}
