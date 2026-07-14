package com.axion11.visualops.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OneDriveService {

    private static final String SCOPES = "Files.Read.All offline_access User.Read";
    private static final String GRAPH_ME_DRIVE = "https://graph.microsoft.com/v1.0/me/drive";
    private static final String GRAPH_ROOT_CHILDREN = "https://graph.microsoft.com/v1.0/me/drive/root/children?$select=id,folder,file&$top=200";

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${onedrive.client-id:}")
    private String clientId;

    @Value("${onedrive.client-secret:}")
    private String clientSecret;

    @Value("${onedrive.redirect-uri}")
    private String redirectUri;

    @Value("${onedrive.tenant-id:common}")
    private String tenantId;

    public boolean isConfigured() {
        return clientId != null && !clientId.isEmpty() && clientSecret != null && !clientSecret.isEmpty();
    }

    private String authEndpoint() {
        return "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/authorize";
    }

    private String tokenEndpoint() {
        return "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token";
    }

    public String buildAuthorizationUrl(String state) {
        return authEndpoint()
                + "?client_id=" + urlEncode(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&response_mode=query"
                + "&scope=" + urlEncode(SCOPES)
                + "&state=" + urlEncode(state);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public TokenResponse exchangeCodeForTokens(String authCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", authCode);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");
        form.add("scope", SCOPES);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                tokenEndpoint(), new HttpEntity<>(form, headers), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null) throw new RuntimeException("Empty token response");

        return new TokenResponse(
                (String) body.get("access_token"),
                (String) body.get("refresh_token"),
                ((Number) body.getOrDefault("expires_in", 3600)).longValue()
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public TokenResponse refreshAccessToken(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        form.add("scope", SCOPES);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                tokenEndpoint(), new HttpEntity<>(form, headers), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null) throw new RuntimeException("Empty refresh response");

        String newRefresh = (String) body.get("refresh_token");
        return new TokenResponse(
                (String) body.get("access_token"),
                newRefresh != null ? newRefresh : refreshToken,
                ((Number) body.getOrDefault("expires_in", 3600)).longValue()
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public StorageQuota getStorageQuota(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                GRAPH_ME_DRIVE, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null) return new StorageQuota(0L, 0L);

        Map<String, Object> quota = (Map<String, Object>) body.get("quota");
        if (quota == null) return new StorageQuota(0L, 0L);
        Long used = parseLong(quota.get("used"));
        Long total = parseLong(quota.get("total"));
        return new StorageQuota(used != null ? used : 0L, total != null ? total : 0L);
    }

    /** Recursively counts files (not folders) by walking the drive. Caps total API calls. */
    public int getFileCount(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        int[] count = {0};
        int[] callsRemaining = {50}; // cap at 50 API calls per sync
        walkChildren(GRAPH_ROOT_CHILDREN, headers, count, callsRemaining);
        return count[0];
    }

    /**
     * Resolves a slash-separated folder name path to a OneDrive item ID. Returns null if not found.
     * Returns "root" if the path is empty/null.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public String resolveFolderIdByPath(String accessToken, String folderPath) {
        if (folderPath == null || folderPath.trim().isEmpty()) return "root";
        String trimmed = folderPath.replaceAll("^/+|/+$", "");
        if (trimmed.isEmpty()) return "root";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        try {
            // OneDrive Graph supports path-based addressing: /me/drive/root:/Foo/Bar
            String url = "https://graph.microsoft.com/v1.0/me/drive/root:/" + trimmed + ":?$select=id,folder";
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> body = response.getBody();
            if (body == null) return null;
            if (!body.containsKey("folder")) return null;
            return (String) body.get("id");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Walks the OneDrive folder tree and returns a flat list of files with path/checksum/modified time.
     */
    public List<DriveFile> walkFolder(String accessToken, String rootItemId, int maxFiles, int maxApiCalls) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        String rootUrl = (rootItemId == null || "root".equalsIgnoreCase(rootItemId))
                ? "https://graph.microsoft.com/v1.0/me/drive/root/children?$select=id,name,folder,file,size,lastModifiedDateTime&$top=200"
                : "https://graph.microsoft.com/v1.0/me/drive/items/" + rootItemId + "/children?$select=id,name,folder,file,size,lastModifiedDateTime&$top=200";

        List<DriveFile> results = new java.util.ArrayList<>();
        java.util.Deque<String[]> stack = new java.util.ArrayDeque<>();
        stack.push(new String[]{rootUrl, ""});

        int[] callsRemaining = {maxApiCalls};
        while (!stack.isEmpty() && results.size() < maxFiles && callsRemaining[0] > 0) {
            String[] entry = stack.pop();
            walkForFiles(entry[0], entry[1], headers, results, stack, callsRemaining, maxFiles);
        }
        return results;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void walkForFiles(String url, String parentPath, HttpHeaders headers, List<DriveFile> results,
                              java.util.Deque<String[]> stack, int[] callsRemaining, int maxFiles) {
        if (callsRemaining[0] <= 0 || results.size() >= maxFiles) return;
        callsRemaining[0]--;

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null) return;

        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("value");
        if (items != null) {
            for (Map<String, Object> item : items) {
                String name = (String) item.get("name");
                String id = (String) item.get("id");
                String childPath = parentPath.isEmpty() ? name : parentPath + "/" + name;

                if (item.containsKey("folder")) {
                    String childUrl = "https://graph.microsoft.com/v1.0/me/drive/items/" + id + "/children?$select=id,name,folder,file,size,lastModifiedDateTime&$top=200";
                    stack.push(new String[]{childUrl, childPath});
                } else if (item.containsKey("file")) {
                    DriveFile df = new DriveFile();
                    df.id = id;
                    df.name = name;
                    df.path = childPath;
                    df.sizeBytes = parseLong(item.get("size"));
                    df.modifiedTimeIso = (String) item.get("lastModifiedDateTime");

                    Map<String, Object> fileObj = (Map<String, Object>) item.get("file");
                    if (fileObj != null) {
                        df.mimeType = (String) fileObj.get("mimeType");
                        Map<String, Object> hashes = (Map<String, Object>) fileObj.get("hashes");
                        if (hashes != null) {
                            // Prefer sha1Hash if present; otherwise quickXorHash
                            Object sha1 = hashes.get("sha1Hash");
                            Object qxor = hashes.get("quickXorHash");
                            df.providerHash = sha1 != null ? sha1.toString() : (qxor != null ? qxor.toString() : null);
                        }
                    }
                    results.add(df);
                    if (results.size() >= maxFiles) return;
                }
            }
        }

        String next = (String) body.get("@odata.nextLink");
        if (next != null) stack.push(new String[]{next, parentPath});
    }

    /**
     * Lists the immediate children (folders + files) of a folder, one page, for interactive browsing.
     * Use folderId "root" (or null) for the drive's root.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<DriveEntry> listChildren(String accessToken, String folderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        String url = (folderId == null || folderId.isBlank() || "root".equalsIgnoreCase(folderId))
                ? "https://graph.microsoft.com/v1.0/me/drive/root/children?$select=id,name,folder,file,size,lastModifiedDateTime&$top=200"
                : "https://graph.microsoft.com/v1.0/me/drive/items/" + folderId + "/children?$select=id,name,folder,file,size,lastModifiedDateTime&$top=200";

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> body = response.getBody();
        List<DriveEntry> out = new java.util.ArrayList<>();
        if (body == null) return out;

        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("value");
        if (items == null) return out;
        for (Map<String, Object> item : items) {
            DriveEntry e = new DriveEntry();
            e.id = (String) item.get("id");
            e.name = (String) item.get("name");
            e.isFolder = item.containsKey("folder");
            e.sizeBytes = parseLong(item.get("size"));
            e.modifiedTimeIso = (String) item.get("lastModifiedDateTime");
            Object fileObj = item.get("file");
            if (fileObj instanceof Map<?, ?> fm) {
                Object mt = fm.get("mimeType");
                e.mimeType = mt != null ? mt.toString() : null;
            }
            out.add(e);
        }
        out.sort((a, b) -> a.isFolder == b.isFolder ? a.name.compareToIgnoreCase(b.name) : (a.isFolder ? -1 : 1));
        return out;
    }

    /** Fetches metadata for a single file (used when importing an individually-picked file). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public DriveFile getFileMetadata(String accessToken, String fileId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        String url = "https://graph.microsoft.com/v1.0/me/drive/items/" + fileId + "?$select=id,name,file,size,lastModifiedDateTime";
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null) throw new RuntimeException("Empty file metadata response");

        DriveFile df = new DriveFile();
        df.id = (String) body.get("id");
        df.name = (String) body.get("name");
        df.path = df.name;
        df.sizeBytes = parseLong(body.get("size"));
        df.modifiedTimeIso = (String) body.get("lastModifiedDateTime");
        Object fileObj = body.get("file");
        if (fileObj instanceof Map<?, ?> fm) {
            Object mt = fm.get("mimeType");
            df.mimeType = mt != null ? mt.toString() : null;
            Object hashesObj = fm.get("hashes");
            if (hashesObj instanceof Map<?, ?> hashes) {
                Object sha1 = hashes.get("sha1Hash");
                Object qxor = hashes.get("quickXorHash");
                df.providerHash = sha1 != null ? sha1.toString() : (qxor != null ? qxor.toString() : null);
            }
        }
        return df;
    }

    /** Downloads a file's raw bytes from OneDrive. */
    public byte[] downloadFile(String accessToken, String fileId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        String url = "https://graph.microsoft.com/v1.0/me/drive/items/" + fileId + "/content";
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        return response.getBody();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void walkChildren(String url, HttpHeaders headers, int[] count, int[] callsRemaining) {
        if (callsRemaining[0] <= 0) return;
        callsRemaining[0]--;

        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null) return;

        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("value");
        if (items != null) {
            for (Map<String, Object> item : items) {
                if (item.containsKey("file")) {
                    count[0]++;
                } else if (item.containsKey("folder") && callsRemaining[0] > 0) {
                    String id = (String) item.get("id");
                    if (id != null) {
                        walkChildren("https://graph.microsoft.com/v1.0/me/drive/items/" + id + "/children?$select=id,folder,file&$top=200",
                                headers, count, callsRemaining);
                    }
                }
            }
        }

        String next = (String) body.get("@odata.nextLink");
        if (next != null && callsRemaining[0] > 0) {
            walkChildren(next, headers, count, callsRemaining);
        }
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
        public String path;
        public String providerHash;   // sha1 or quickXorHash
        public String modifiedTimeIso;
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
