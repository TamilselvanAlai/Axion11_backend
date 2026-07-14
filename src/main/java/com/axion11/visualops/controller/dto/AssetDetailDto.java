package com.axion11.visualops.controller.dto;

import lombok.Data;
import java.util.List;

@Data
public class AssetDetailDto {
    private Long id;
    private String externalId;
    private String thumbnail;
    private String name;
    private String status;
    private Integer aiScore;
    private String dimensions;
    private String fileSize;
    private String uploadedBy;
    private String uploadDate;
    private String assignedToName;
    private Integer commentsCount;
    private List<CommentDto> comments;
}
