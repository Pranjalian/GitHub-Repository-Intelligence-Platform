package com.repointelligence.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.repointelligence.common.model.AnalysisCategory;
import com.repointelligence.common.model.ExecutionStatus;
import com.repointelligence.common.model.Severity;
import com.repointelligence.report.application.SectionAggregator;
import com.repointelligence.report.application.SourceUrls;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SectionAggregatorTests {

	@Test
	void allCompletedIsCompleted() {
		assertThat(SectionAggregator.aggregate(List.of(ExecutionStatus.COMPLETED, ExecutionStatus.COMPLETED)))
			.isEqualTo(ExecutionStatus.COMPLETED);
	}

	@Test
	void aWarningOrAFailureAlongsideASuccessIsCompletedWithWarnings() {
		assertThat(SectionAggregator.aggregate(List.of(ExecutionStatus.COMPLETED, ExecutionStatus.COMPLETED_WITH_WARNINGS)))
			.isEqualTo(ExecutionStatus.COMPLETED_WITH_WARNINGS);
		assertThat(SectionAggregator.aggregate(List.of(ExecutionStatus.COMPLETED, ExecutionStatus.FAILED)))
			.isEqualTo(ExecutionStatus.COMPLETED_WITH_WARNINGS);
	}

	@Test
	void whenNothingCompletedTheMostSignificantFailureWins() {
		assertThat(SectionAggregator.aggregate(List.of(ExecutionStatus.UNAVAILABLE, ExecutionStatus.FAILED)))
			.isEqualTo(ExecutionStatus.FAILED);
		assertThat(SectionAggregator.aggregate(List.of(ExecutionStatus.NOT_CONFIGURED, ExecutionStatus.UNAVAILABLE)))
			.isEqualTo(ExecutionStatus.UNAVAILABLE);
		assertThat(SectionAggregator.aggregate(List.of(ExecutionStatus.TIMED_OUT))).isEqualTo(ExecutionStatus.TIMED_OUT);
	}

	@Test
	void noAnalyzerMeansNotSupported() {
		assertThat(SectionAggregator.aggregate(List.of())).isEqualTo(ExecutionStatus.NOT_SUPPORTED);
	}

	@Test
	void notAnalyzedSectionsHaveNullCountsNotZeros() {
		for (ExecutionStatus status : List.of(ExecutionStatus.FAILED, ExecutionStatus.UNAVAILABLE,
				ExecutionStatus.NOT_CONFIGURED, ExecutionStatus.NOT_SUPPORTED, ExecutionStatus.TIMED_OUT,
				ExecutionStatus.NOT_ANALYZED)) {
			assertThat(SectionAggregator.counts(status, Map.of(Severity.HIGH, 3L))).as(status.name()).isNull();
		}
	}

	@Test
	void analyzedSectionsHaveACountForEverySeverityIncludingRealZeros() {
		Map<Severity, Long> counts = SectionAggregator.counts(ExecutionStatus.COMPLETED, Map.of(Severity.HIGH, 3L));

		assertThat(counts).containsEntry(Severity.HIGH, 3L)
			.containsEntry(Severity.CRITICAL, 0L)
			.containsEntry(Severity.INFO, 0L);
		assertThat(counts).hasSize(Severity.values().length);
	}

	@Test
	void summariesAreHonest() {
		assertThat(SectionAggregator.summary(AnalysisCategory.SECURITY, ExecutionStatus.UNAVAILABLE, null))
			.contains("unavailable");
		assertThat(SectionAggregator.summary(AnalysisCategory.SECURITY, ExecutionStatus.COMPLETED,
				SectionAggregator.counts(ExecutionStatus.COMPLETED, Map.of()))).contains("no security findings");
	}

	@Test
	void sourceUrlPointsAtTheExactCommitAndLines() {
		String sha = "a".repeat(40);

		assertThat(SourceUrls.blobUrl("o", "r", sha, "src/My File.java", 10, 12))
			.isEqualTo("https://github.com/o/r/blob/" + sha + "/src/My%20File.java#L10-L12");
		assertThat(SourceUrls.blobUrl("o", "r", sha, "a/B.java", 7, 7)).endsWith("/a/B.java#L7");
		assertThat(SourceUrls.blobUrl("o", "r", sha, "a/B.java", null, null)).endsWith("/a/B.java");
		assertThat(SourceUrls.blobUrl("o", "r", sha, null, 1, 2)).isNull();
	}

}
