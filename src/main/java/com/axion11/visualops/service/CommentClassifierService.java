package com.axion11.visualops.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keyword-based semantic classifier that categorises QC review comments
 * into feedback categories/subcategories matching the Feedback Heatmap.
 */
@Service
public class CommentClassifierService {

    public record Classification(String category, String subcategory, String severity) {}

    // Category constants
    private static final String PHOTOGRAPHY_SOURCE = "PHOTOGRAPHY_SOURCE";
    private static final String POST_PRODUCTION_PROCESS = "POST_PRODUCTION_PROCESS";
    private static final String POST_PRODUCTION_RETOUCHING = "POST_PRODUCTION_RETOUCHING";
    private static final String PRODUCTION_MANAGEMENT = "PRODUCTION_MANAGEMENT";

    // Ordered map: first match wins. More specific patterns come first.
    private static final Map<Pattern, String[]> RULES = new LinkedHashMap<>();

    static {
        // --- Photography Source ---
        addRule("dimension|size.*wrong|incorrect.*size|wrong.*dimension|crop.*size|aspect.*ratio",
                PHOTOGRAPHY_SOURCE, "Dimension", "medium");
        addRule("resolution|low.?res|high.?res|megapixel|mp.*too|pixel.*count",
                PHOTOGRAPHY_SOURCE, "Resolution", "medium");
        addRule("sharp|blur|blurry|soft.*image|not.*sharp|lack.*sharp|unsharp|motion.*blur",
                PHOTOGRAPHY_SOURCE, "Sharpness", "high");
        addRule("expos|over.?expos|under.?expos|bright|dark.*image|too.*dark|too.*bright|highlight.*blown|shadow.*crush",
                PHOTOGRAPHY_SOURCE, "Exposure", "high");
        addRule("focus|out.*focus|mis.?focus|bokeh.*wrong|focal|auto.?focus|soft.*focus",
                PHOTOGRAPHY_SOURCE, "Focus", "high");

        // --- Post Production - Process Error ---
        addRule("fram|crop|compos|rule.*third|center.*frame|off.*center|head.*room|negative.*space",
                POST_PRODUCTION_PROCESS, "Framing", "medium");
        addRule("align|straight|tilt|level|skew|rotat|horizon",
                POST_PRODUCTION_PROCESS, "Alignment", "low");
        addRule("ppi|dpi|print.*resolution|72.*dpi|300.*dpi|web.*resolution",
                POST_PRODUCTION_PROCESS, "PPI/DPI", "medium");
        addRule("instruction|guideline|spec|brief|requirement|not.*follow|doesn.*match|style.*guide",
                POST_PRODUCTION_PROCESS, "Instructions not followed", "high");
        // Generic dimension in post-production context (catch-all after photography)
        addRule("resize|rescale|output.*size|canvas.*size|artboard",
                POST_PRODUCTION_PROCESS, "Dimension", "medium");

        // --- Post Production - Retouching Error ---
        addRule("skin|blemish|acne|wrinkle|smooth|retouch.*skin|complexion|pore|skin.*tone",
                POST_PRODUCTION_RETOUCHING, "Skin", "low");
        addRule("garment|cloth|fabric|wrinkle.*cloth|fold|crease|iron|steam|apparel",
                POST_PRODUCTION_RETOUCHING, "Garment", "medium");
        addRule("product.*defect|product.*flaw|scratch|dent|dust.*product|product.*retouch|product.*clean",
                POST_PRODUCTION_RETOUCHING, "Product", "high");
        addRule("clutter|distract|unwanted.*object|remove.*object|messy|clean.*up|stray",
                POST_PRODUCTION_RETOUCHING, "Clutter", "medium");
        addRule("background|bg|backdrop|background.*color|background.*remov|cutout|silhouette|mask.*edge",
                POST_PRODUCTION_RETOUCHING, "Background", "high");
        addRule("color.*correct|white.*balance|wb|colour|hue|saturation|vibran|tone.*curve|grade|grading|cast",
                POST_PRODUCTION_RETOUCHING, "Color Correction", "medium");

        // --- Production Management ---
        addRule("delay.*assign|assign.*late|not.*assigned|waiting.*assign|pending.*assign",
                PRODUCTION_MANAGEMENT, "Delay - Assignment", "medium");
        addRule("delay.*post.?prod|post.?prod.*late|retouch.*delay|edit.*delay|slow.*turnaround",
                PRODUCTION_MANAGEMENT, "Delay - Postproduction", "high");
        addRule("delay.*desc|desc.*missing|no.*description|desc.*late|missing.*info|incomplete.*desc",
                PRODUCTION_MANAGEMENT, "Delay - Description", "low");
        addRule("misinformation|wrong.*info|incorrect.*info|mislead|error.*info|inaccurate",
                PRODUCTION_MANAGEMENT, "Misinformation", "medium");
        addRule("sample.*delay|sample.*late|info.*delay|reference.*delay|waiting.*sample|no.*sample",
                PRODUCTION_MANAGEMENT, "Sample/Info Delay", "medium");

        // Broader production/delay catch-all
        addRule("delay|late|overdue|behind.*schedule|deadline|missed.*deadline|slow",
                PRODUCTION_MANAGEMENT, "Delay - Assignment", "medium");
    }

    private static void addRule(String regex, String category, String subcategory, String severity) {
        RULES.put(Pattern.compile("(?i)" + regex), new String[]{category, subcategory, severity});
    }

    /**
     * Classify a comment's text into a feedback category, subcategory, and severity.
     * Returns null if no category matches.
     */
    public Classification classify(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase();
        for (var entry : RULES.entrySet()) {
            if (entry.getKey().matcher(lower).find()) {
                String[] val = entry.getValue();
                return new Classification(val[0], val[1], val[2]);
            }
        }
        return null;
    }
}
