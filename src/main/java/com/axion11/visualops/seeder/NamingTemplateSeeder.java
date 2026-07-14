package com.axion11.visualops.seeder;

import com.axion11.visualops.models.NamingTemplate;
import com.axion11.visualops.models.NamingTemplateView;
import com.axion11.visualops.repository.NamingTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
@RequiredArgsConstructor
public class NamingTemplateSeeder implements CommandLineRunner {

    private final NamingTemplateRepository repository;

    @Override
    public void run(String... args) {
        if (repository.count() > 0) return;

        // Template 1: Standard Product Photography
        NamingTemplate t1 = NamingTemplate.builder()
                .name("Standard Product Photography")
                .sector("product-only")
                .skuBase("PROD")
                .build();
        addView(t1, 0, "Front View", "{SKU}_1", "{SKU}_Front");
        addView(t1, 1, "Back View", "{SKU}_2", "{SKU}_Back");
        addView(t1, 2, "Left View", "{SKU}_3", "{SKU}_Left");
        addView(t1, 3, "Right View", "{SKU}_4", "{SKU}_Right");
        addView(t1, 4, "Top View", "{SKU}_5", "{SKU}_Top");
        addView(t1, 5, "Detail View", "{SKU}_6", "{SKU}_Detail");
        repository.save(t1);

        // Template 2: Fashion On-Model
        NamingTemplate t2 = NamingTemplate.builder()
                .name("Fashion On-Model Template")
                .sector("on-model")
                .skuBase("FASH")
                .build();
        addView(t2, 0, "Front View", "{SKU}_1", "{SKU}_Front");
        addView(t2, 1, "Back View", "{SKU}_2", "{SKU}_Back");
        addView(t2, 2, "Left View", "{SKU}_3", "{SKU}_Left");
        addView(t2, 3, "Right View", "{SKU}_4", "{SKU}_Right");
        addView(t2, 4, "Full Length", "{SKU}_5", "{SKU}_FullLength");
        addView(t2, 5, "Close Up", "{SKU}_6", "{SKU}_CloseUp");
        repository.save(t2);

        // Template 3: Accessories Close-up
        NamingTemplate t3 = NamingTemplate.builder()
                .name("Accessories Close-up")
                .sector("accessories")
                .skuBase("ACC")
                .build();
        addView(t3, 0, "Front View", "{SKU}_1", "{SKU}_Front");
        addView(t3, 1, "Back View", "{SKU}_2", "{SKU}_Back");
        addView(t3, 2, "Side View", "{SKU}_3", "{SKU}_Side");
        addView(t3, 3, "Detail View", "{SKU}_4", "{SKU}_Detail");
        repository.save(t3);
    }

    private void addView(NamingTemplate template, int order, String viewName, String existing, String target) {
        NamingTemplateView view = NamingTemplateView.builder()
                .template(template)
                .viewName(viewName)
                .existingPattern(existing)
                .targetPattern(target)
                .sortOrder(order)
                .build();
        template.getViews().add(view);
    }
}
