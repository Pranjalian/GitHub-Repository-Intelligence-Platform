package com.repointelligence.architecture.application;

import com.repointelligence.architecture.api.SymbolDTO;
import com.repointelligence.architecture.domain.AnnotationInfo;
import com.repointelligence.architecture.domain.CodeSymbol;
import com.repointelligence.architecture.domain.SymbolType;
import com.repointelligence.architecture.infrastructure.persistence.CodeSymbolRepository;
import com.repointelligence.common.exception.ResourceNotFoundException;
import com.repointelligence.report.application.ReportService;
import com.repointelligence.report.application.SourceUrls;
import com.repointelligence.repository.domain.TrackedRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One symbol of the latest completed run of a snapshot, with its members. 404 for anything else. */
@Service
@Transactional(readOnly = true)
public class SymbolQueryService {

	private final ReportService reports;

	private final CodeSymbolRepository symbols;

	public SymbolQueryService(ReportService reports, CodeSymbolRepository symbols) {
		this.reports = reports;
		this.symbols = symbols;
	}

	public SymbolDTO symbol(UUID repositoryId, UUID snapshotId, UUID symbolId) {
		ReportService.ReportScope scope = reports.scope(repositoryId, snapshotId, null);
		CodeSymbol symbol = symbols.findById(symbolId)
			.filter(s -> s.getSnapshot().getId().equals(snapshotId) && s.getRun().getId().equals(scope.run().getId()))
			.orElseThrow(() -> new ResourceNotFoundException("Symbol not found in the latest completed analysis of this snapshot."));
		TrackedRepository repository = scope.snapshot().getRepository();
		String sha = scope.snapshot().getCommitSha();
		List<SymbolDTO> members = symbols.findBySnapshotIdAndRunIdAndParentId(snapshotId, scope.run().getId(), symbolId)
			.stream()
			.sorted(Comparator.comparing((CodeSymbol s) -> s.getStartLine() == null ? Integer.MAX_VALUE : s.getStartLine())
				.thenComparing(CodeSymbol::getFullyQualifiedName))
			.map(m -> toDto(m, repository, sha, List.of()))
			.toList();
		return toDto(symbol, repository, sha, members);
	}

	private static SymbolDTO toDto(CodeSymbol s, TrackedRepository repository, String sha, List<SymbolDTO> members) {
		return new SymbolDTO(s.getId(), s.getSymbolType(), name(s), s.getFullyQualifiedName(), s.getSignature(),
				s.getFilePath(), s.getStartLine(), s.getEndLine(),
				SourceUrls.blobUrl(repository.getOwner(), repository.getName(), sha, s.getFilePath(), s.getStartLine(),
						s.getEndLine()),
				s.getAnnotations() == null ? List.of() : s.getAnnotations().stream().map(AnnotationInfo::simpleName).toList(),
				members);
	}

	private static String name(CodeSymbol s) {
		if ((s.getSymbolType() == SymbolType.METHOD || s.getSymbolType() == SymbolType.CONSTRUCTOR) && s.getSignature() != null) {
			return s.getSignature();
		}
		String fqn = s.getFullyQualifiedName();
		int hash = fqn.indexOf('#');
		if (hash >= 0) {
			return fqn.substring(hash + 1);
		}
		return fqn.substring(fqn.lastIndexOf('.') + 1);
	}

}
