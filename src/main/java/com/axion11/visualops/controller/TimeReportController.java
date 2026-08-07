package com.axion11.visualops.controller;

import com.axion11.visualops.models.dto.AssetEditReportRowDto;
import com.axion11.visualops.models.dto.PayrollRowDto;
import com.axion11.visualops.service.AssetEditSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Weekly/monthly asset-editing time reports, for payroll — who worked on what, how long
 *  (idle-corrected), and what that comes out to at the project's hourly rate. Restricted to
 *  roles with billing responsibility; this exposes every user's logged hours and derived pay,
 *  not just the caller's own. */
@RestController
@RequestMapping("/api/reports/time")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'BILLING_MANAGER')")
@RequiredArgsConstructor
public class TimeReportController {

    private final AssetEditSessionService assetEditSessionService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> report(
            @RequestParam("from") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "userId", required = false) Long userId) {

        List<AssetEditReportRowDto> detailRows = assetEditSessionService.getReport(from, to, userId);
        List<PayrollRowDto> payrollRows = assetEditSessionService.getPayrollRollup(from, to, userId);

        long totalActiveSeconds = detailRows.stream().mapToLong(AssetEditReportRowDto::getActiveSeconds).sum();
        BigDecimal totalEstimatedPay = payrollRows.stream()
                .map(PayrollRowDto::getEstimatedPay)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", to);
        result.put("detailRows", detailRows);
        result.put("payrollRows", payrollRows);
        result.put("totalActiveSeconds", totalActiveSeconds);
        result.put("totalEstimatedPay", totalEstimatedPay);
        return ResponseEntity.ok(result);
    }

    /** {@code type=payroll} (default) exports the per-user/per-project pay rollup — the columns
     *  needed to run payroll. {@code type=detail} exports the underlying per-asset sessions, for
     *  audit trail behind those numbers. */
    @GetMapping("/export.csv")
    public ResponseEntity<String> exportCsv(
            @RequestParam("from") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "type", defaultValue = "payroll") String type) {

        String csv = "detail".equalsIgnoreCase(type)
                ? detailCsv(assetEditSessionService.getReport(from, to, userId))
                : payrollCsv(assetEditSessionService.getPayrollRollup(from, to, userId));

        String fileName = String.format("axion-time-report-%s_%s-%s.csv", type, from, to);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(csv);
    }

    private String payrollCsv(List<PayrollRowDto> rows) {
        StringBuilder sb = new StringBuilder("User,Project,Active Hours,Rate Per Hour,Estimated Pay\n");
        for (PayrollRowDto row : rows) {
            double hours = Math.round(row.getActiveSeconds() / 36.0) / 100.0;
            sb.append(csvField(row.getUserName())).append(',')
                    .append(csvField(row.getProjectName())).append(',')
                    .append(hours).append(',')
                    .append(row.getRatePerHour()).append(',')
                    .append(row.getEstimatedPay()).append('\n');
        }
        return sb.toString();
    }

    private String detailCsv(List<AssetEditReportRowDto> rows) {
        StringBuilder sb = new StringBuilder("Date,User,Project,Asset,Started,Ended,Active Minutes,Idle Minutes Excluded,End Reason\n");
        for (AssetEditReportRowDto row : rows) {
            long activeMinutes = Math.round(row.getActiveSeconds() / 60.0);
            long idleMinutes = Math.round(row.getIdleSecondsExcluded() / 60.0);
            sb.append(row.getStartedAt().toLocalDate()).append(',')
                    .append(csvField(row.getUserName())).append(',')
                    .append(csvField(row.getProjectName())).append(',')
                    .append(csvField(row.getFileName())).append(',')
                    .append(row.getStartedAt()).append(',')
                    .append(row.getEndedAt()).append(',')
                    .append(activeMinutes).append(',')
                    .append(idleMinutes).append(',')
                    .append(csvField(row.getEndReason())).append('\n');
        }
        return sb.toString();
    }

    /** Quotes a CSV field only when it actually needs it (contains a comma/quote/newline) —
     *  wrapping every field unconditionally is also valid CSV, but this keeps the common case
     *  (plain names) readable when the file is eyeballed in a text editor. */
    private String csvField(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
