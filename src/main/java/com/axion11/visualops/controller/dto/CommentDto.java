package com.axion11.visualops.controller.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CommentDto {
    private Long id;
    private String text;
    private String authorName;
    private LocalDateTime createdAt;
    private boolean resolved;
    private String annotationImageUrl;
    private Double markX;
    private Double markY;
}
