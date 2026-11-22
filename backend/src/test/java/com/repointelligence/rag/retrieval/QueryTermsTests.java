package com.repointelligence.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QueryTermsTests {

	@Test
	void identifiersMatchWholeOrByTheirParts() {
		QueryTerms terms = QueryTerms.of("findByLastName owner_id HTTPClient");

		assertThat(terms.allOf()).isEqualTo(
				"(findbylastname | (find & by & last & name)) & (ownerid | (owner & id)) & (httpclient | (http & client))");
	}

	@Test
	void questionsDropStopwordsAndMatchAnyMeaningfulWord() {
		QueryTerms terms = QueryTerms.of("Where is the OrderService created?");

		assertThat(terms.meaningful()).containsExactly("OrderService", "created");
		assertThat(terms.anyOf()).isEqualTo("(orderservice | (order & service)) | (created | creat:*)");
	}

	@Test
	void querySyntaxFromTheUserNeverReachesTheTsquery() {
		QueryTerms terms = QueryTerms.of("a:* & !b | c' <-> (d) \\ ;DROP TABLE x");

		assertThat(terms.allOf()).doesNotContain("!", "'", "<->", "\\", ";").contains("drop & table");
		assertThat(terms.allOf().replace(":*", "")).doesNotContain(":");
		assertThat(QueryTerms.of("&|!()").isEmpty()).isTrue();
		assertThat(QueryTerms.of("&|!()").allOf()).isNull();
	}

	@Test
	void termsAreBounded() {
		String many = String.join(" ", java.util.stream.IntStream.range(0, 50).mapToObj(i -> "word" + i).toList());

		assertThat(QueryTerms.of(many).tokens()).hasSize(QueryTerms.MAX_TERMS);
	}

	@Test
	void wordsAlsoMatchByALightStemButIdentifiersStayExact() {
		assertThat(QueryTerms.stem("policies")).isEqualTo("polic");
		assertThat(QueryTerms.stem("customers")).isEqualTo("customer");
		assertThat(QueryTerms.stem("generated")).isEqualTo("generat");
		assertThat(QueryTerms.stem("class")).isNull();
		assertThat(QueryTerms.stem("classes")).isEqualTo("class");
		assertThat(QueryTerms.stem("ids")).isNull();
		assertThat(QueryTerms.stem("owner")).isNull();
		assertThat(QueryTerms.of("discount policies").allOf()).isEqualTo("discount & (policies | polic:*)");
		assertThat(QueryTerms.of("OrderServices").allOf()).isEqualTo("(orderservices | (order & services))");
	}

}
