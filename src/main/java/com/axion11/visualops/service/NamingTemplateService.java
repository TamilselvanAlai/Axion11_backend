package com.axion11.visualops.service;

import com.axion11.visualops.controller.dto.NamingTemplateDto;
import com.axion11.visualops.controller.dto.NamingTemplateViewDto;
import com.axion11.visualops.models.NamingTemplate;
import com.axion11.visualops.models.NamingTemplateView;
import com.axion11.visualops.repository.NamingTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NamingTemplateService {

    private final NamingTemplateRepository repository;

    private NamingTemplateViewDto toViewDto(NamingTemplateView v) {
        return new NamingTemplateViewDto(v.getId(), v.getViewName(), v.getExistingPattern(), v.getTargetPattern(), v.getSortOrder());
    }

    private NamingTemplateDto toDto(NamingTemplate t) {
        List<NamingTemplateViewDto> viewDtos = t.getViews() == null ? List.of()
                : t.getViews().stream().map(this::toViewDto).collect(Collectors.toList());
        return new NamingTemplateDto(t.getId(), t.getName(), t.getSector(), t.getSkuBase(), t.getCreatedAt(), viewDtos);
    }

    @Transactional(readOnly = true)
    public List<NamingTemplateDto> getAll() {
        return repository.findAll().stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public NamingTemplateDto getById(Long id) {
        return repository.findById(id).map(this::toDto)
                .orElseThrow(() -> new RuntimeException("Template not found: " + id));
    }

    @Transactional
    public NamingTemplateDto create(NamingTemplateDto req) {
        NamingTemplate template = NamingTemplate.builder()
                .name(req.name())
                .sector(req.sector())
                .skuBase(req.skuBase())
                .build();

        if (req.views() != null) {
            for (int i = 0; i < req.views().size(); i++) {
                NamingTemplateViewDto vd = req.views().get(i);
                NamingTemplateView view = NamingTemplateView.builder()
                        .template(template)
                        .viewName(vd.viewName())
                        .existingPattern(vd.existingPattern())
                        .targetPattern(vd.targetPattern())
                        .sortOrder(vd.sortOrder() != null ? vd.sortOrder() : i)
                        .build();
                template.getViews().add(view);
            }
        }

        return toDto(repository.save(template));
    }

    @Transactional
    public NamingTemplateDto update(Long id, NamingTemplateDto req) {
        NamingTemplate template = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Template not found: " + id));

        template.setName(req.name());
        template.setSector(req.sector());
        template.setSkuBase(req.skuBase());

        // Replace all views
        template.getViews().clear();
        if (req.views() != null) {
            for (int i = 0; i < req.views().size(); i++) {
                NamingTemplateViewDto vd = req.views().get(i);
                NamingTemplateView view = NamingTemplateView.builder()
                        .template(template)
                        .viewName(vd.viewName())
                        .existingPattern(vd.existingPattern())
                        .targetPattern(vd.targetPattern())
                        .sortOrder(vd.sortOrder() != null ? vd.sortOrder() : i)
                        .build();
                template.getViews().add(view);
            }
        }

        return toDto(repository.save(template));
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }
}
