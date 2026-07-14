package com.axion11.visualops.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "assets")
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String externalId; // matching the React ID like '1-3-1'
    private String thumbnail;

    @Column(nullable = false)
    private String name;

    private String status;
    private Integer aiScore;
    private String dimensions;
    private String fileSize;
    private String uploadedBy;
    private String uploadDate;
    private Integer commentsCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    @Builder.Default
    @OneToMany(mappedBy = "asset", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<Comment> comments = new java.util.ArrayList<>();
}
