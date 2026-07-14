package com.axion11.visualops.service;

import com.axion11.visualops.models.ImageQcResult;
import com.axion11.visualops.models.ImageUpload;
import com.axion11.visualops.models.Project;
import com.axion11.visualops.repository.ImageQcResultRepository;
import com.axion11.visualops.repository.ImageUploadRepository;
import com.axion11.visualops.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageQcService {

    private final ImageUploadRepository imageUploadRepository;
    private final ImageQcResultRepository imageQcResultRepository;
    private final ProjectRepository projectRepository;

    private static final List<String> MARKETPLACES = List.of("AMAZON", "SHOPIFY", "EBAY", "ETSY");

    // ── Marketplace-specific rules ──────────────────────────────────────────

    // Format constraints per marketplace
    private static final Map<String, Set<String>> FORMAT_RULES = Map.of(
            "AMAZON",  Set.of("jpg", "jpeg", "png", "tiff", "tif", "gif"),
            "SHOPIFY", Set.of("jpg", "jpeg", "png", "gif", "webp"),
            "EBAY",    Set.of("jpg", "jpeg", "png", "tiff", "tif", "gif", "webp"),
            "ETSY",    Set.of("jpg", "jpeg", "png", "gif")
    );

    // Minimum dimension (longest side) per marketplace
    private static final Map<String, Integer> MIN_DIMENSION = Map.of(
            "AMAZON",  1000,
            "SHOPIFY", 2048,
            "EBAY",    500,
            "ETSY",    2000
    );

    // Max file size in bytes per marketplace
    private static final Map<String, Long> MAX_FILE_SIZE = Map.of(
            "AMAZON",  10L * 1024 * 1024,   // 10 MB
            "SHOPIFY", 20L * 1024 * 1024,    // 20 MB
            "EBAY",    12L * 1024 * 1024,     // 12 MB
            "ETSY",    1L * 1024 * 1024       // 1 MB recommended
    );

    // Preferred aspect ratios per marketplace
    private static final Map<String, Set<String>> ASPECT_RATIOS = Map.of(
            "AMAZON",  Set.of("1:1"),
            "SHOPIFY", Set.of("1:1"),
            "EBAY",    Set.of("1:1", "4:3", "3:4"),
            "ETSY",    Set.of("4:3", "3:4", "3:2", "2:3")
    );

    // Whether sRGB is strictly required
    private static final Map<String, Boolean> SRGB_REQUIRED = Map.of(
            "AMAZON", true,
            "SHOPIFY", false,
            "EBAY", false,
            "ETSY", true
    );

    /**
     * Runs QC on all images not yet fully PASSED, using each image's project marketplaces.
     */
    @Async
    public void runBatchQc() {
        List<ImageUpload> uploads = imageUploadRepository
                .findByImageQualityQcCheckIsNullOrImageQualityQcCheckNot("PASSED");

        log.info("QC batch started — {} images to process", uploads.size());
        HttpClient httpClient = HttpClient.newHttpClient();

        for (ImageUpload upload : uploads) {
            try {
                List<String> projectMarketplaces = getProjectMarketplaces(upload);
                if (projectMarketplaces.isEmpty()) {
                    log.debug("Skipping QC for upload id={} — no marketplaces configured on project", upload.getId());
                    continue;
                }
                runQcForUpload(httpClient, upload, projectMarketplaces);
            } catch (Exception e) {
                log.error("QC failed for upload id={}: {}", upload.getId(), e.getMessage());
                upload.setImageQualityQcCheck("ERROR");
                upload.setQcDetails("QC processing error: " + e.getMessage());
                imageUploadRepository.save(upload);
            }
        }

        log.info("QC batch complete");
    }

    /**
     * Run QC on a single image for its project's configured marketplaces.
     */
    @Transactional
    public List<MarketplaceQcResult> validateSingle(Long uploadId) {
        ImageUpload upload = imageUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found: " + uploadId));

        List<String> projectMarketplaces = getProjectMarketplaces(upload);
        if (projectMarketplaces.isEmpty()) {
            log.warn("No marketplaces configured for upload id={}", uploadId);
            return List.of();
        }

        HttpClient httpClient = HttpClient.newHttpClient();
        return runQcForUpload(httpClient, upload, projectMarketplaces);
    }

    /**
     * Run QC on a single image for specific marketplaces only.
     */
    @Transactional
    public List<MarketplaceQcResult> validateForMarketplaces(Long uploadId, List<String> marketplaces) {
        if (marketplaces == null || marketplaces.isEmpty()) return List.of();

        ImageUpload upload = imageUploadRepository.findById(uploadId)
                .orElseThrow(() -> new RuntimeException("Upload not found: " + uploadId));

        HttpClient httpClient = HttpClient.newHttpClient();
        return runQcForUpload(httpClient, upload, marketplaces);
    }

    /**
     * Get stored QC results for an image.
     */
    public List<MarketplaceQcResult> getQcResults(Long uploadId) {
        List<ImageQcResult> results = imageQcResultRepository.findByImageUploadId(uploadId);
        return results.stream()
                .map(r -> new MarketplaceQcResult(r.getMarketplace(), r.getStatus(), r.getDetails(),
                        r.getCheckedAt() != null ? r.getCheckedAt().toString() : null))
                .toList();
    }

    private List<String> getProjectMarketplaces(ImageUpload upload) {
        if (upload.getProject() == null) return List.of();
        // Eagerly fetch project to avoid LazyInitializationException in async context
        Project project = projectRepository.findById(upload.getProject().getId()).orElse(null);
        if (project != null && project.getMarketplaces() != null && !project.getMarketplaces().isEmpty()) {
            return List.of(project.getMarketplaces().split(","));
        }
        return List.of();
    }

    private List<MarketplaceQcResult> runQcForUpload(HttpClient httpClient, ImageUpload upload, List<String> targetMarketplaces) {
        String extension = getExtension(upload.getFileName()).toLowerCase();

        // Download image once, reuse for all marketplace checks
        byte[] imageBytes = downloadImage(httpClient, upload.getPublicUrl());
        ImageAnalysis analysis = analyzeImage(imageBytes, extension);

        List<MarketplaceQcResult> results = new ArrayList<>();
        boolean allPassed = true;
        List<String> overallIssues = new ArrayList<>();

        for (String marketplace : targetMarketplaces) {
            MarketplaceQcResult result = validateForMarketplace(marketplace, upload, extension, analysis);
            results.add(result);

            // Persist result
            ImageQcResult entity = imageQcResultRepository
                    .findByImageUploadIdAndMarketplace(upload.getId(), marketplace)
                    .orElse(ImageQcResult.builder()
                            .imageUpload(upload)
                            .marketplace(marketplace)
                            .build());
            entity.setStatus(result.status());
            entity.setDetails(result.details());
            entity.setCheckedAt(java.time.LocalDateTime.now());
            imageQcResultRepository.save(entity);

            if (!"PASSED".equals(result.status())) {
                allPassed = false;
                overallIssues.add(marketplace + ": " + result.status());
            }
        }

        // Update summary on the upload itself
        upload.setImageQualityQcCheck(allPassed ? "PASSED" : "WARNING");
        upload.setQcDetails(allPassed ? "All marketplaces passed" : String.join("; ", overallIssues));
        imageUploadRepository.save(upload);

        return results;
    }

    private MarketplaceQcResult validateForMarketplace(String marketplace, ImageUpload upload,
                                                        String extension, ImageAnalysis analysis) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Format check
        Set<String> allowedFormats = FORMAT_RULES.get(marketplace);
        if (!allowedFormats.contains(extension)) {
            errors.add("Format '" + extension + "' not supported (allowed: " + String.join(", ", allowedFormats) + ")");
        }

        // File size check
        long maxSize = MAX_FILE_SIZE.get(marketplace);
        if (upload.getFileSize() != null && upload.getFileSize() > maxSize) {
            String severity = "ETSY".equals(marketplace) ? "warn" : "error";
            String msg = "File size " + formatSize(upload.getFileSize()) + " exceeds " + formatSize(maxSize) + " limit";
            if ("error".equals(severity)) errors.add(msg); else warnings.add(msg);
        }

        if (analysis == null) {
            errors.add("Could not analyze image");
            return resolve(marketplace, errors, warnings);
        }

        // Dimension check
        int minDim = MIN_DIMENSION.get(marketplace);
        int longestSide = Math.max(analysis.width, analysis.height);
        if (longestSide < minDim) {
            if ("AMAZON".equals(marketplace) || "EBAY".equals(marketplace)) {
                errors.add("Min " + minDim + "px required (actual: " + longestSide + "px)");
            } else {
                warnings.add("Recommended min " + minDim + "px (actual: " + longestSide + "px)");
            }
        }

        // Color space
        if (Boolean.TRUE.equals(SRGB_REQUIRED.get(marketplace)) && !analysis.isSrgb) {
            errors.add("Color profile must be sRGB");
        }

        // Aspect ratio
        Set<String> preferredRatios = ASPECT_RATIOS.get(marketplace);
        if (analysis.ratio != null && !preferredRatios.contains(analysis.ratio)) {
            warnings.add("Aspect ratio " + analysis.ratio + " — preferred: " + String.join(", ", preferredRatios));
        }

        // DPI check (universal baseline)
        if (analysis.dpi > 0 && analysis.dpi < 72) {
            warnings.add("Low resolution: " + String.format("%.0f", analysis.dpi) + " DPI (min 72)");
        }

        // Sharpness (universal baseline BL-06)
        if (analysis.sharpness >= 0 && analysis.sharpness < 50.0) {
            warnings.add("Image appears blurry (sharpness=" + String.format("%.1f", analysis.sharpness) + ")");
        }

        // Amazon-specific: recommend white background
        if ("AMAZON".equals(marketplace)) {
            if (analysis.isWhiteBackground != null && !analysis.isWhiteBackground) {
                warnings.add("White background recommended for main product image");
            }
        }

        return resolve(marketplace, errors, warnings);
    }

    // ── Image Analysis (done once per image) ──────────────────────────────────

    private ImageAnalysis analyzeImage(byte[] imageBytes, String extension) {
        if (imageBytes == null) return null;

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) return null;

            int width = image.getWidth();
            int height = image.getHeight();

            ColorSpace cs = image.getColorModel().getColorSpace();
            boolean isSrgb = cs.getType() == ColorSpace.TYPE_RGB;

            double dpi = extractDpi(imageBytes, extension);
            String ratio = simplifyRatio(width, height);
            double sharpness = computeSharpness(image);
            Boolean isWhiteBg = detectWhiteBackground(image);

            return new ImageAnalysis(width, height, isSrgb, dpi, ratio, sharpness, isWhiteBg);
        } catch (Exception e) {
            log.warn("Image analysis failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Simple white background detection: samples border pixels and checks
     * if the majority are close to white (R,G,B > 240).
     */
    private Boolean detectWhiteBackground(BufferedImage image) {
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            int whiteCount = 0;
            int sampleCount = 0;

            // Sample top/bottom rows and left/right columns
            for (int x = 0; x < w; x += Math.max(1, w / 50)) {
                for (int y : new int[]{0, h - 1}) {
                    int rgb = image.getRGB(x, y);
                    if (isNearWhite(rgb)) whiteCount++;
                    sampleCount++;
                }
            }
            for (int y = 0; y < h; y += Math.max(1, h / 50)) {
                for (int x : new int[]{0, w - 1}) {
                    int rgb = image.getRGB(x, y);
                    if (isNearWhite(rgb)) whiteCount++;
                    sampleCount++;
                }
            }

            if (sampleCount == 0) return null;
            return (double) whiteCount / sampleCount > 0.8;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isNearWhite(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r > 240 && g > 240 && b > 240;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MarketplaceQcResult resolve(String marketplace, List<String> errors, List<String> warnings) {
        String status;
        if (!errors.isEmpty()) {
            status = "REJECTED";
        } else if (!warnings.isEmpty()) {
            status = "WARNING";
        } else {
            status = "PASSED";
        }

        List<String> all = new ArrayList<>();
        all.addAll(errors);
        all.addAll(warnings);
        String details = all.isEmpty() ? "All requirements met" : String.join("; ", all);

        if (details.length() > 2000) {
            details = details.substring(0, 1997) + "...";
        }

        return new MarketplaceQcResult(marketplace, status, details, java.time.LocalDateTime.now().toString());
    }

    private String getExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String simplifyRatio(int width, int height) {
        double aspect = (double) width / height;
        double[][] standards = {
                {1.0, 1, 1}, {0.8, 4, 5}, {0.75, 3, 4}, {1.778, 16, 9},
                {1.25, 5, 4}, {1.333, 4, 3}, {0.5625, 9, 16},
                {1.5, 3, 2}, {0.667, 2, 3}
        };

        for (double[] std : standards) {
            if (Math.abs(aspect - std[0]) < 0.05) {
                return (int) std[1] + ":" + (int) std[2];
            }
        }

        int g = gcd(width, height);
        return (width / g) + ":" + (height / g);
    }

    private int gcd(int a, int b) {
        while (b != 0) { int t = b; b = a % b; a = t; }
        return a;
    }

    private double extractDpi(byte[] imageBytes, String extension) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return 0;

            ImageReader reader = readers.next();
            reader.setInput(iis);
            IIOMetadata metadata = reader.getImageMetadata(0);
            if (metadata == null) return 0;

            for (String formatName : metadata.getMetadataFormatNames()) {
                org.w3c.dom.Node root = metadata.getAsTree(formatName);
                double dpi = findDpiInNode(root);
                if (dpi > 0) return dpi;
            }
            reader.dispose();
        } catch (Exception e) {
            log.debug("Could not extract DPI for {}: {}", extension, e.getMessage());
        }
        return 0;
    }

    private double findDpiInNode(org.w3c.dom.Node node) {
        if (node == null) return 0;
        if ("HorizontalPixelSize".equals(node.getNodeName()) || "VerticalPixelSize".equals(node.getNodeName())) {
            org.w3c.dom.NamedNodeMap attrs = node.getAttributes();
            if (attrs != null) {
                org.w3c.dom.Node valueAttr = attrs.getNamedItem("value");
                if (valueAttr != null) {
                    try {
                        double mmPerPixel = Double.parseDouble(valueAttr.getNodeValue());
                        if (mmPerPixel > 0) return 25.4 / mmPerPixel;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        org.w3c.dom.NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            double dpi = findDpiInNode(children.item(i));
            if (dpi > 0) return dpi;
        }
        return 0;
    }

    private double computeSharpness(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int stepX = Math.max(1, w / 512), stepY = Math.max(1, h / 512);
        int gw = w / stepX, gh = h / stepY;
        if (gw < 3 || gh < 3) return 100;

        int[][] gray = new int[gh][gw];
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++) {
                int rgb = image.getRGB(x * stepX, y * stepY);
                gray[y][x] = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
            }

        double sum = 0, sumSq = 0;
        int count = 0;
        for (int y = 1; y < gh - 1; y++)
            for (int x = 1; x < gw - 1; x++) {
                int lap = gray[y - 1][x] + gray[y + 1][x] + gray[y][x - 1] + gray[y][x + 1] - 4 * gray[y][x];
                sum += lap;
                sumSq += (double) lap * lap;
                count++;
            }

        if (count == 0) return 100;
        double mean = sum / count;
        return (sumSq / count) - (mean * mean);
    }

    private byte[] downloadImage(HttpClient httpClient, String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(imageUrl)).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) return response.body();
            log.warn("Failed to download image {}: status {}", imageUrl, response.statusCode());
            return null;
        } catch (Exception e) {
            log.warn("Failed to download image {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record MarketplaceQcResult(String marketplace, String status, String details, String checkedAt) {}

    private record ImageAnalysis(int width, int height, boolean isSrgb, double dpi,
                                  String ratio, double sharpness, Boolean isWhiteBackground) {}
}
