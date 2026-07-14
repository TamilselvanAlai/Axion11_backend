package com.axion11.visualops.controller.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class AddCommentRequest {
    @NotBlank(message = "Comment text is required")
    private String text;

    /** Base64-encoded annotated image snapshot (optional) */
    private String annotationImage;

    /** Pixel coordinates (natural image resolution) of the spot this comment marks, if any. */
    private Double markX;
    private Double markY;
}
