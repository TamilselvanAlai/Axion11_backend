package com.axion11.visualops.models.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkSessionSummaryDto {
    private long activeSecondsToday;
    private int assetsEditedToday;
    private long activeSecondsYesterday;
    private int assetsEditedYesterday;
    /** Lifetime idle-corrected active-editing total for this user across every session ever
     *  recorded — "how long have I actually been working", distinct from timeInAppSecondsAllTime
     *  below ("how long has the app been open"). */
    private long activeSecondsAllTime;
    /** Wall-clock login-to-logout time today, regardless of idle — "how long was I in the app",
     *  as opposed to activeSecondsToday's "how long was I actually working". Showing both is what
     *  makes idle exclusion visible/trustworthy instead of a single unexplained number. */
    private long timeInAppSecondsToday;
    private long timeInAppSecondsAllTime;
}
