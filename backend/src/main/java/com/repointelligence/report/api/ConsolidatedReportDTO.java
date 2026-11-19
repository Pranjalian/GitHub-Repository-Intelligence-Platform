package com.repointelligence.report.api;

import java.util.List;

public record ConsolidatedReportDTO(RepositoryRefDTO repository, ReportSnapshotDTO snapshot, ReportRunDTO run,
		List<TechnologyDTO> technologies, StructureDTO structure, List<CapabilityMatrixRowDTO> capabilityMatrix,
		List<ReportSectionDTO> sections, List<LimitationDTO> limitations) {
}
