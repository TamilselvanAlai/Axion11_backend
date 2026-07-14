package com.axion11.visualops.models;

import jakarta.persistence.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "naming_template_views")
public class NamingTemplateView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private NamingTemplate template;

    @Column(nullable = false)
    private String viewName;

    @Column(nullable = false)
    private String existingPattern;

    @Column(nullable = false)
    private String targetPattern;

    @Builder.Default
    private Integer sortOrder = 0;
}
