package com.repointelligence.report.api;

import com.repointelligence.common.model.ExecutionStatus;
import java.util.List;

/**
 * Coverage of a snapshot. {@code status} is COMPLETED only when a real report was read; otherwise NOT_ANALYZED
 * with an explanatory {@code message} and no records. Coverage is never estimated and never shown as 0%.
 */
public record CoverageReportDTO(ExecutionStatus status, String message, List<CoverageRecordDTO> records) {
}
