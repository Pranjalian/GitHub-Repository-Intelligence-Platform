package com.repointelligence.report.api;

import java.util.List;

/** {@code dependencyCount} is the total the dependencies endpoint pages over; null when no dependency analyzer completed. */
public record ConsolidatedReportDTO(RepositoryRefDTO repository, ReportSnapshotDTO snapshot, ReportRunDTO run,
		List<TechnologyDTO> technologies, StructureDTO structure, List<CapabilityMatrixRowDTO> capabilityMatrix,
		List<ReportSectionDTO> sections, List<LimitationDTO> limitations, Long dependencyCount) {
}
