package com.axion11.visualops.service;

import com.axion11.visualops.models.Batch;
import com.axion11.visualops.models.CloudConnection;
import com.axion11.visualops.models.ImageUpload;
import com.axion11.visualops.models.SyncedFile;
import com.axion11.visualops.repository.BatchRepository;
import com.axion11.visualops.repository.CloudConnectionRepository;
import com.axion11.visualops.repository.ImageUploadRepository;
import com.axion11.visualops.repository.SyncedFileRepository;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudSyncService {

    /** Hard caps to protect against runaway syncs. */
    private static final int MAX_FILES_PER_SYNC = 2000;
    private static final int MAX_API_CALLS_PER_SYNC = 100;
    private static final long MAX_FILE_SIZE_BYTES = 500L * 1024 * 1024; // 500 MB

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // images
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "tiff", "tif", "bmp",
            // raw
            "cr2", "cr3", "nef", "arw", "dng", "raf", "orf", "rw2",
            // videos
            "mp4", "mov", "avi", "mkv", "webm", "m4v", "mpg", "mpeg", "wmv"
    );

    private static final Set<String> SKIPPED_EXTENSIONS = Set.of(
            // docs
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp", "key", "pages", "numbers",
            // archives
            "zip", "rar", "7z", "tar", "gz", "bz2"
    );

    private final CloudConnectionRepository connectionRepository;
    private final SyncedFileRepository syncedFileRepository;
    private final ImageUploadRepository imageUploadRepository;
    private final BatchRepository batchRepository;
    private final CloudConnectionService cloudConnectionService;
    private final GoogleDriveService googleDriveService;
    private final OneDriveService oneDriveService;

    @Value("${gcs.bucket.name}")
    private String bucketName;

    @Transactional
    public SyncResult sync(Long connectionId, Long targetBatchId, String rootFolderPath) {
        CloudConnection conn = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new RuntimeException("Connection not found"));
        Batch batch = batchRepository.findById(targetBatchId)
                .orElseThrow(() -> new RuntimeException("Target batch not found"));

        String accessToken = cloudConnectionService.getValidAccessToken(conn);
        SyncResult result = new SyncResult();

        boolean isGoogle = CloudConnectionService.PROVIDER_GOOGLE_DRIVE.equals(conn.getProvider());
        if (isGoogle) {
            String rootFolderId = googleDriveService.resolveFolderIdByPath(accessToken, rootFolderPath);
            if (rootFolderId == null) {
                throw new RuntimeException("Source folder not found in Google Drive: " + rootFolderPath);
            }
            List<GoogleDriveService.DriveFile> files = googleDriveService.walkFolder(
                    accessToken, rootFolderId, MAX_FILES_PER_SYNC, MAX_API_CALLS_PER_SYNC);
            for (GoogleDriveService.DriveFile f : files) {
                processFile(conn, batch, f.id, f.name, f.path, f.md5Hex, f.modifiedTimeIso, f.sizeBytes, f.mimeType, result, true);
            }
            result.totalFoundInSource = files.size();
        } else if (CloudConnectionService.PROVIDER_ONEDRIVE.equals(conn.getProvider())) {
            String rootFolderId = oneDriveService.resolveFolderIdByPath(accessToken, rootFolderPath);
            if (rootFolderId == null) {
                throw new RuntimeException("Source folder not found in OneDrive: " + rootFolderPath);
            }
            List<OneDriveService.DriveFile> files = oneDriveService.walkFolder(
                    accessToken, rootFolderId, MAX_FILES_PER_SYNC, MAX_API_CALLS_PER_SYNC);
            for (OneDriveService.DriveFile f : files) {
                processFile(conn, batch, f.id, f.name, f.path, f.providerHash, f.modifiedTimeIso, f.sizeBytes, f.mimeType, result, false);
            }
            result.totalFoundInSource = files.size();
        } else {
            throw new IllegalStateException("Unknown provider: " + conn.getProvider());
        }

        // Mark as-not-seen rows orphaned for this connection
        markOrphaned(conn.getId(), result);

        // Touch connection's last synced timestamp
        conn.setLastSyncedAt(LocalDateTime.now());
        conn.setStatus(CloudConnectionService.STATUS_CONNECTED);
        connectionRepository.save(conn);

        return result;
    }

    /**
     * Imports a user-picked selection (from the interactive folder browser) into a single target batch.
     * Folder items are walked recursively (same as {@link #sync}); file items are fetched individually.
     * Unlike {@link #sync}, this never marks unrelated previously-synced files as orphaned — it only
     * touches what the user explicitly selected.
     */
    @Transactional
    public SyncResult importItems(Long connectionId, Long targetBatchId, List<ImportItem> items) {
        CloudConnection conn = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new RuntimeException("Connection not found"));
        Batch batch = batchRepository.findById(targetBatchId)
                .orElseThrow(() -> new RuntimeException("Target batch not found"));

        String accessToken = cloudConnectionService.getValidAccessToken(conn);
        SyncResult result = new SyncResult();
        boolean isGoogle = CloudConnectionService.PROVIDER_GOOGLE_DRIVE.equals(conn.getProvider());

        for (ImportItem item : items) {
            if (item.isFolder) {
                if (isGoogle) {
                    List<GoogleDriveService.DriveFile> files = googleDriveService.walkFolder(
                            accessToken, item.id, MAX_FILES_PER_SYNC, MAX_API_CALLS_PER_SYNC);
                    for (GoogleDriveService.DriveFile f : files) {
                        processFile(conn, batch, f.id, f.name, f.path, f.md5Hex, f.modifiedTimeIso, f.sizeBytes, f.mimeType, result, true);
                    }
                    result.totalFoundInSource += files.size();
                } else {
                    List<OneDriveService.DriveFile> files = oneDriveService.walkFolder(
                            accessToken, item.id, MAX_FILES_PER_SYNC, MAX_API_CALLS_PER_SYNC);
                    for (OneDriveService.DriveFile f : files) {
                        processFile(conn, batch, f.id, f.name, f.path, f.providerHash, f.modifiedTimeIso, f.sizeBytes, f.mimeType, result, false);
                    }
                    result.totalFoundInSource += files.size();
                }
            } else {
                if (isGoogle) {
                    GoogleDriveService.DriveFile f = googleDriveService.getFileMetadata(accessToken, item.id);
                    processFile(conn, batch, f.id, f.name, f.name, f.md5Hex, f.modifiedTimeIso, f.sizeBytes, f.mimeType, result, true);
                } else {
                    OneDriveService.DriveFile f = oneDriveService.getFileMetadata(accessToken, item.id);
                    processFile(conn, batch, f.id, f.name, f.name, f.providerHash, f.modifiedTimeIso, f.sizeBytes, f.mimeType, result, false);
                }
                result.totalFoundInSource++;
            }
        }

        conn.setLastSyncedAt(LocalDateTime.now());
        conn.setStatus(CloudConnectionService.STATUS_CONNECTED);
        connectionRepository.save(conn);
        return result;
    }

    public static class ImportItem {
        public final String id;
        public final String name;
        public final boolean isFolder;
        public ImportItem(String id, String name, boolean isFolder) {
            this.id = id;
            this.name = name;
            this.isFolder = isFolder;
        }
    }

    private void processFile(CloudConnection conn, Batch batch, String providerFileId, String fileName,
                             String providerPath, String providerChecksum, String modifiedTimeIso,
                             Long sizeBytes, String mimeType, SyncResult result, boolean isGoogle) {
        String ext = extensionOf(fileName);
        if (SKIPPED_EXTENSIONS.contains(ext)) {
            result.skippedUnsupported++;
            return;
        }
        if (!ALLOWED_EXTENSIONS.contains(ext) && !(mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("video/")))) {
            result.skippedUnsupported++;
            return;
        }
        if (sizeBytes != null && sizeBytes > MAX_FILE_SIZE_BYTES) {
            SyncedFile sf = getOrNew(conn, providerFileId);
            sf.setProviderPath(providerPath);
            sf.setProviderSizeBytes(sizeBytes);
            sf.setStatus("SKIPPED_TOO_LARGE");
            sf.setLastErrorMessage("File larger than " + MAX_FILE_SIZE_BYTES + " bytes");
            sf.setLastSyncedAt(LocalDateTime.now());
            syncedFileRepository.save(sf);
            result.skippedTooLarge++;
            return;
        }

        LocalDateTime providerMod = parseIso(modifiedTimeIso);
        Optional<SyncedFile> existingOpt = syncedFileRepository.findByCloudConnectionIdAndProviderFileId(conn.getId(), providerFileId);
        SyncedFile sf = existingOpt.orElse(null);

        // Fast path: nothing changed on the provider side
        if (sf != null && Objects.equals(sf.getProviderChecksum(), providerChecksum)
                && Objects.equals(sf.getProviderModifiedAt(), providerMod)
                && sf.getImageUpload() != null) {
            result.skippedUnchanged++;
            sf.setLastSyncedAt(LocalDateTime.now());
            if (!"ORPHANED".equals(sf.getStatus())) sf.setStatus("SYNCED");
            syncedFileRepository.save(sf);
            return;
        }

        // If we already have a local copy, check whether user modified it since last sync
        if (sf != null && sf.getImageUpload() != null && sf.getLocalChecksumAtSync() != null) {
            try {
                Storage storage = StorageOptions.getDefaultInstance().getService();
                Blob blob = storage.get(BlobId.of(bucketName, sf.getImageUpload().getGcsPath()));
                if (blob != null && blob.getMd5() != null && !sf.getLocalChecksumAtSync().equals(blob.getMd5())) {
                    sf.setStatus("LOCALLY_MODIFIED");
                    sf.setLastSyncedAt(LocalDateTime.now());
                    sf.setLastErrorMessage("Skipped: local file modified in Axion11");
                    syncedFileRepository.save(sf);
                    result.skippedLocallyModified++;
                    return;
                }
            } catch (Exception e) {
                log.warn("Failed to check local md5 for sync file {}: {}", providerFileId, e.getMessage());
            }
        }

        // Download + upload
        try {
            byte[] data = isGoogle
                    ? googleDriveService.downloadFile(cloudConnectionService.getValidAccessToken(conn), providerFileId)
                    : oneDriveService.downloadFile(cloudConnectionService.getValidAccessToken(conn), providerFileId);
            if (data == null) throw new RuntimeException("Empty download");

            String objectName = "synced/" + conn.getId() + "/" + providerFileId + "-" + safe(fileName);
            Storage storage = StorageOptions.getDefaultInstance().getService();
            BlobId blobId = BlobId.of(bucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(mimeType != null ? mimeType : "application/octet-stream")
                    .build();
            Blob blob = storage.create(blobInfo, data);
            String publicUrl = "https://storage.googleapis.com/" + bucketName + "/" + objectName;

            ImageUpload upload = (sf != null && sf.getImageUpload() != null) ? sf.getImageUpload() : new ImageUpload();
            upload.setFileName(fileName);
            upload.setGcsPath(objectName);
            upload.setPublicUrl(publicUrl);
            upload.setContentType(blobInfo.getContentType());
            upload.setFileSize((long) data.length);
            upload.setBatch(batch);
            upload.setProject(batch.getProject());
            upload.setSourcePath(providerPath);
            if (upload.getUploadedBy() == null) upload.setUploadedBy(conn.getUser());
            if (upload.getUploadStatus() == null) upload.setUploadStatus("COMPLETED");
            if (upload.getExternalId() == null) upload.setExternalId("drive-" + providerFileId);
            upload = imageUploadRepository.save(upload);

            if (sf == null) sf = getOrNew(conn, providerFileId);
            sf.setProviderPath(providerPath);
            sf.setProviderChecksum(providerChecksum);
            sf.setProviderModifiedAt(providerMod);
            sf.setProviderSizeBytes(sizeBytes != null ? sizeBytes : (long) data.length);
            sf.setImageUpload(upload);
            sf.setLocalChecksumAtSync(blob.getMd5());
            sf.setStatus("SYNCED");
            sf.setLastErrorMessage(null);
            sf.setLastSyncedAt(LocalDateTime.now());
            syncedFileRepository.save(sf);

            if (existingOpt.isEmpty()) result.created++;
            else result.updated++;
        } catch (Exception e) {
            log.error("Sync failed for file {} ({}): {}", providerFileId, fileName, e.getMessage());
            result.errors++;
        }
    }

    private void markOrphaned(Long connectionId, SyncResult result) {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(5);
        List<SyncedFile> all = syncedFileRepository.findByCloudConnectionId(connectionId);
        for (SyncedFile sf : all) {
            if (sf.getLastSyncedAt() != null && sf.getLastSyncedAt().isBefore(cutoff) && !"ORPHANED".equals(sf.getStatus())) {
                sf.setStatus("ORPHANED");
                syncedFileRepository.save(sf);
                result.orphaned++;
            }
        }
    }

    private SyncedFile getOrNew(CloudConnection conn, String providerFileId) {
        return syncedFileRepository.findByCloudConnectionIdAndProviderFileId(conn.getId(), providerFileId)
                .orElseGet(() -> SyncedFile.builder()
                        .cloudConnection(conn)
                        .providerFileId(providerFileId)
                        .status("PENDING")
                        .build());
    }

    private static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String safe(String s) {
        return s == null ? "file" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static LocalDateTime parseIso(String iso) {
        if (iso == null) return null;
        try {
            return LocalDateTime.ofInstant(Instant.parse(iso), ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    public static class SyncResult {
        public int totalFoundInSource;
        public int created;
        public int updated;
        public int skippedUnchanged;
        public int skippedLocallyModified;
        public int skippedUnsupported;
        public int skippedTooLarge;
        public int orphaned;
        public int errors;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalFoundInSource", totalFoundInSource);
            m.put("created", created);
            m.put("updated", updated);
            m.put("skippedUnchanged", skippedUnchanged);
            m.put("skippedLocallyModified", skippedLocallyModified);
            m.put("skippedUnsupported", skippedUnsupported);
            m.put("skippedTooLarge", skippedTooLarge);
            m.put("orphaned", orphaned);
            m.put("errors", errors);
            return m;
        }
    }
}
