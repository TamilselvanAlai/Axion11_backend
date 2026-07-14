package com.axion11.visualops.controller;

import com.axion11.visualops.models.SearchAudit;
import com.axion11.visualops.models.User;
import com.axion11.visualops.repository.SearchAuditRepository;
import com.axion11.visualops.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/search-audit")
@RequiredArgsConstructor
public class SearchAuditController {

    private final SearchAuditRepository searchAuditRepository;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<Map<String, Object>> logSearch(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserDetails userDetails) {

        String query = (String) body.get("query");
        String searchType = (String) body.get("searchType");
        Integer matchCount = body.get("matchCount") != null ? ((Number) body.get("matchCount")).intValue() : 0;

        User user = null;
        if (userDetails != null) {
            user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);
        }

        SearchAudit audit = SearchAudit.builder()
                .query(query)
                .searchType(searchType)
                .matchCount(matchCount)
                .user(user)
                .build();

        audit = searchAuditRepository.save(audit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", audit.getId());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/recent")
    public ResponseEntity<List<Map<String, Object>>> getRecentSearches(
            @AuthenticationPrincipal UserDetails userDetails) {

        List<SearchAudit> searches = searchAuditRepository.findTop20ByOrderBySearchedAtDesc();

        List<Map<String, Object>> result = searches.stream().map(s -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", s.getId());
            map.put("query", s.getQuery());
            map.put("searchType", s.getSearchType());
            map.put("matchCount", s.getMatchCount());
            map.put("searchedAt", s.getSearchedAt().toString());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
