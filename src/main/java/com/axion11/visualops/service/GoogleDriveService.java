package com.axion11.visualops.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleDriveService {

    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke";
    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String ABOUT_ENDPOINT = "https://www.googleapis.com/drive/v3/about?fields=storageQuota,user";
    private static final String FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files";
    // drive.readonly: allows listing all Drive folders/files via the API so the app can show
    // an inline file browser without needing the Google Picker. Sensitive scope — requires
    // Google verification for production apps with 100+ users, or keep the OAuth app in
    // "Testing" mode and add users to the test-user list to avoid the assessment.
    private static final String SCOPES = "https://www.googleapis.com/auth/drive.readonly";

    @Value("${google.picker.api-key:}")
    private String pickerApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${google.drive.client-id:}")
    private String clientId;

    @Value("${google.drive.client-secret:}")
    private String clientSecret;

    @Value("${google.drive.redirect-uri}")
    private String redirectUri;

    public boolean isConfigured() {
        return clientId != null && !clientId.isEmpty() && clientSecret != null && !clientSecret.isEmpty();
    }

    public String getPickerApiKey() {
        return pickerApiKey;
    }

    public String buildAuthorizationUrl(String state) {
        return buildAuthorizationUrl(state, null);
    }

    /**
     * @param customRedirectUri when set, overrides the configured {@code google.drive.redirect-uri}
     *                          — lets a caller (e.g. the web app running on a dev/prod origin other
     *                          than the one baked into server config) receive the callback itself,
     *                          as long as that exact URI is also registered with the OAuth client.
     */
    public String buildAuthorizationUrl(String state, String customRedirectUri) {
        String finalRedirectUri = (customRedirectUri != null && !customRedirectUri.isEmpty()) ? customRedirectUri : redirectUri;
        return AUTH_ENDPOINT
                + "?client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(finalRedirectUri)
                + "&response_type=code"
                + "&scope=" + urlEncode(SCOPES)
                + "&access_type=offline"
                + "&prompt=" + urlEncode("select_account consent")
                + "&state=" + urlEncode(state);
    }

    /** Exchange authorization code for access + refresh tokens. */
    public TokenResponse exchangeCodeForTokens(String authCode) {
        return exchangeCodeForTokens(authCode, null);
    }

    /** Same as above, but with the redirect_uri that was actually used for the authorize step
     *  (must match exactly, or Google rejects the exchange). */
    @SuppressWarnings("unchecked")
    public TokenResponse exchangeCodeForTokens(String authCode, String customRedirectUri) {
        String finalRedirectUri = (customRedirectUri != null && !customRedirectUri.isEmpty()) ? customRedirectUri : redirectUri;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", authCode);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", finalRedirectUri);
        form.add("grant_type", "authorization_code");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                TOKEN_ENDPOINT, new HttpEntity<>(form, headers), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null) throw new RuntimeException("Empty token response");

        return new TokenResponse(
                (String) body.get("access_token"),
                (String) body.get("refresh_token"),
                ((Number) body.getOrDefault("expires_in", 3600)).longValue()
        );
    }

    /** Use refresh token to get a new access token. */
    @SuppressWarnings("unchecked")
    public TokenResponse refreshAccessToken(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "refresh_token");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                TOKEN_ENDPOINT, new HttpEntity<>(form, headers), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null) throw new RuntimeException("Empty refresh response");

        // Refresh response does not include a new refresh_token
        return new TokenResponse(
                (String) body.get("access_token"),
                refreshToken,
                ((Number) body.getOrDefault("expires_in", 3600)).longValue()
        );
    }

    public void revokeToken(String token) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("token", token);
            restTemplate.postForEntity(REVOKE_ENDPOINT, new HttpEntity<>(form, headers), String.class);
        } catch (Exception e) {
            log.warn("Google token revoke failed: {}", e.getMessage());
        }
    }

    /** Returns storage quota: usedBytes, totalBytes. */
    @SuppressWarnings("unchecked")
    public StorageQuota getStorageQuota(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                ABOUT_ENDPOINT, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null) return new StorageQuota(0L, 0L);

        Map<String, Object> quota = (Map<String, Object>) body.get("storageQuota");
        if (quota == null) return new StorageQuota(0L, 0L);

        Long used = parseLong(quota.get("usage"));
        Long limit = parseLong(quota.get("limit"));
        return new StorageQuota(used != null ? used : 0L, limit != null ? limit : 0L);
    }

    /**
     * Resolves a slash-separated folder name path (e.g. "Marketing/Spring 2026") to a Drive folder ID.
     * Walks from "root". Returns null if any segment isn't found.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public String resolveFolderIdByPath(String accessToken, String folderPath) {
        if (folderPath == null || folderPath.trim().isEmpty()) return "root";
        String trimmed = folderPath.replaceAll("^/+|/+$", "");
        if (trimmed.isEmpty()) return "root";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        String currentId = "root";
        for (String segment : trimmed.split("/")) {
            String q = "'" + currentId + "' in parents and name='" + segment.replace("'", "\\'")
                    + "' and mimeType='application/vnd.google-apps.folder' and trashed=false";
            URI uri = UriComponentsBuilder.fromUriString(FILES_ENDPOINT)
                    .queryParam("q", q)
                    .queryParam("fields", "files(id,name)")
                    .queryParam("pageSize", 1)
                    .build().encode().toUri();

            ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = response.getBody();
            if (body == null) return null;
            java.util.List<Map<String, Object>> files = (java.util.List<Map<String, Object>>) body.get("files");
            if (files == null || files.isEmpty()) return null;
            currentId = (String) files.get(0).get("id");
        }
        return currentId;
    }

    /**
     * Walks the Drive folder tree starting at {@code rootFolderId} ("root" for My Drive) and returns
     * a flat list of files (not folders) with their virtual path, checksum, and modified time.
     * Caps API calls and total files to protect against very large drives.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public java.util.List<DriveFile> walkFolder(String accessToken, String rootFolderId, int maxFiles, int maxApiCalls) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        java.util.List<DriveFile> results = new java.util.ArrayList<>();
        java.util.Deque<String[]> stack = new java.util.ArrayDeque<>();
        stack.push(new String[]{rootFolderId == null ? "root" : rootFolderId, ""});

        int apiCalls = 0;
        while (!stack.isEmpty() && apiCalls < maxApiCalls && results.size() < maxFiles) {
            String[] entry = stack.pop();
            String folderId = entry[0];
            String folderPath = entry[1];
            String pageToken = null;

            do {
                if (apiCalls >= maxApiCalls || results.size() >= maxFiles) break;
                String q = "'" + folderId + "' in parents and trashed=false";
                UriComponentsBuilder b = UriComponentsBuilder.fromUriString(FILES_ENDPOINT)
                        .queryParam("q", q)
                        .queryParam("pageSize", 500)
                        .queryParam("fields", "nextPageToken,files(id,name,mimeType,md5Checksum,modifiedTime,size)");
                if (pageToken != null) b.queryParam("pageToken", pageToken);
                URI uri = b.build().encode().toUri();

                ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
                apiCalls++;
                Map<String, Object> body = response.getBody();
                if (body == null) break;

                java.util.List<Map<String, Object>> files = (java.util.List<Map<String, Object>>) body.get("files");
                if (files != null) {
                    for (Map<String, Object> f : files) {
                        String id = (String) f.get("id");
                        String name = (String) f.get("name");
                        String mimeType = (String) f.get("mimeType");
                        String childPath = folderPath.isEmpty() ? name : folderPath + "/" + name;

                        if ("application/vnd.google-apps.folder".equals(mimeType)) {
                            stack.push(new String[]{id, childPath});
                        } else {
                            DriveFile df = new DriveFile();
                            df.id = id;
                            df.name = name;
                            df.mimeType = mimeType;
                            df.path = childPath;
                            df.md5Hex = (String) f.get("md5Checksum");
                            df.modifiedTimeIso = (String) f.get("modifiedTime");
                            df.sizeBytes = parseLong(f.get("size"));
                            results.add(df);
                            if (results.size() >= maxFiles) break;
                        }
                    }
                }
                pageToken = (String) body.get("nextPageToken");
            } while (pageToken != null);
        }
        return results;
    }

    /**
     * Lists the immediate children (folders + files) of a folder, one page, for interactive browsing.
     * Use folderId "root" (or null) for My Drive's root.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public java.util.List<DriveEntry> listChildren(String accessToken, String folderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        String fid = (folderId == null || folderId.isBlank()) ? "root" : folderId;

        String q = "'" + fid + "' in parents and trashed=false";
        URI uri = UriComponentsBuilder.fromUriString(FILES_ENDPOINT)
                .queryParam("q", q)
                .queryParam("pageSize", 200)
                .queryParam("orderBy", "folder,name")
                .queryParam("fields", "files(id,name,mimeType,size,modifiedTime)")
                .build().encode().toUri();

        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> body = response.getBody();
        java.util.List<DriveEntry> out = new java.util.ArrayList<>();
        if (body == null) return out;

        java.util.List<Map<String, Object>> files = (java.util.List<Map<String, Object>>) body.get("files");
        if (files == null) return out;
        for (Map<String, Object> f : files) {
            DriveEntry e = new DriveEntry();
            e.id = (String) f.get("id");
            e.name = (String) f.get("name");
            e.mimeType = (String) f.get("mimeType");
            e.isFolder = "application/vnd.google-apps.folder".equals(e.mimeType);
            e.sizeBytes = parseLong(f.get("size"));
            e.modifiedTimeIso = (String) f.get("modifiedTime");
            out.add(e);
        }
        return out;
    }

    /** Fetches metadata for a single file (used when importing an individually-picked file). */
    @SuppressWarnings("unchecked")
    public DriveFile getFileMetadata(String accessToken, String fileId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        URI uri = UriComponentsBuilder.fromUriString(FILES_ENDPOINT + "/" + fileId)
                .queryParam("fields", "id,name,mimeType,md5Checksum,modifiedTime,size")
                .build().encode().toUri();
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null) throw new RuntimeException("Empty file metadata response");

        DriveFile df = new DriveFile();
        df.id = (String) body.get("id");
        df.name = (String) body.get("name");
        df.mimeType = (String) body.get("mimeType");
        df.path = df.name;
        df.md5Hex = (String) body.get("md5Checksum");
        df.modifiedTimeIso = (String) body.get("modifiedTime");
        df.sizeBytes = parseLong(body.get("size"));
        return df;
    }

    /** Streams the raw bytes of a file (Drive API media download). */
    public byte[] downloadFile(String accessToken, String fileId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        URI uri = UriComponentsBuilder.fromUriString(FILES_ENDPOINT + "/" + fileId)
                .queryParam("alt", "media")
                .build().encode().toUri();
        ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        return response.getBody();
    }

    /** Count files by paging through /files endpoint. Caps at maxPages to avoid long requests. */
    @SuppressWarnings("unchecked")
    public int getFileCount(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        int total = 0;
        String pageToken = null;
        int maxPages = 20; // up to 20k files per sync (1000 per page)

        for (int i = 0; i < maxPages; i++) {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(FILES_ENDPOINT)
                    .queryParam("q", "trashed=false")
                    .queryParam("pageSize", 1000)
                    .queryParam("fields", "nextPageToken,files(id)");
            if (pageToken != null) builder.queryParam("pageToken", pageToken);
            URI uri = builder.build().encode().toUri();

            ResponseEntity<Map> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = response.getBody();
            if (body == null) break;

            java.util.List<Map<String, Object>> files = (java.util.List<Map<String, Object>>) body.get("files");
            if (files != null) total += files.size();

            pageToken = (String) body.get("nextPageToken");
            if (pageToken == null || pageToken.isEmpty()) break;
        }
        return total;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static Long parseLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    public static class TokenResponse {
        public final String accessToken;
        public final String refreshToken;
        public final long expiresInSeconds;
        public TokenResponse(String a, String r, long e) { this.accessToken = a; this.refreshToken = r; this.expiresInSeconds = e; }
    }

    public static class StorageQuota {
        public final long usedBytes;
        public final long totalBytes;
        public StorageQuota(long u, long t) { this.usedBytes = u; this.totalBytes = t; }
    }

    public static class DriveFile {
        public String id;
        public String name;
        public String mimeType;
        public String path;          // virtual folder path incl. filename
        public String md5Hex;        // provider md5 in hex (null for Google-native docs)
        public String modifiedTimeIso; // RFC 3339 e.g. "2026-04-23T12:34:56.789Z"
        public Long sizeBytes;
    }

    /** A single immediate child returned by {@link #listChildren}, for interactive browsing. */
    public static class DriveEntry {
        public String id;
        public String name;
        public boolean isFolder;
        public String mimeType;
        public Long sizeBytes;
        public String modifiedTimeIso;
    }
}
