package com.repointelligence.analysis.packs.java;

import com.repointelligence.analysis.domain.CoverageScope;
import com.repointelligence.analysis.spi.CoverageResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Reads line and branch counters from a {@code jacoco.xml} report. The report declares a DOCTYPE, so
 * DOCTYPE is allowed, but external DTDs and entities are never loaded (a hostile report cannot read local
 * files or reach the network). Numbers come only from the report; an absent counter stays {@code null}.
 */
public final class JacocoReportParser {

	private JacocoReportParser() {
	}

	/** Parses one report into bundle, package and class records. */
	public static List<CoverageResult> parse(String reportPath, InputStream in) throws IOException, SAXException {
		DocumentBuilder builder = newSafeBuilder();
		Element root = builder.parse(in).getDocumentElement();
		List<CoverageResult> records = new ArrayList<>();
		add(records, reportPath, CoverageScope.BUNDLE, root.getAttribute("name"), root);
		for (Element pkg : children(root, "package")) {
			String packageName = pkg.getAttribute("name");
			add(records, reportPath, CoverageScope.PACKAGE, packageName, pkg);
			for (Element cls : children(pkg, "class")) {
				add(records, reportPath, CoverageScope.CLASS, cls.getAttribute("name"), cls);
			}
		}
		return records;
	}

	static DocumentBuilder newSafeBuilder() {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			// defense in depth: any external resource that is still requested resolves to nothing
			builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
			return builder;
		}
		catch (ParserConfigurationException ex) {
			throw new IllegalStateException("Cannot configure a safe XML parser", ex);
		}
	}

	private static void add(List<CoverageResult> records, String reportPath, CoverageScope scope, String name, Element element) {
		Integer[] line = counter(element, "LINE");
		Integer[] branch = counter(element, "BRANCH");
		records.add(new CoverageResult(reportPath, scope, name == null || name.isEmpty() ? "(unnamed)" : name, line[0], line[1],
				branch[0], branch[1]));
	}

	/** {covered, missed} of the direct child counter of the given type; nulls when the report has none. */
	private static Integer[] counter(Element element, String type) {
		for (Element counter : children(element, "counter")) {
			if (type.equals(counter.getAttribute("type"))) {
				return new Integer[] { parseInt(counter.getAttribute("covered")), parseInt(counter.getAttribute("missed")) };
			}
		}
		return new Integer[] { null, null };
	}

	private static Integer parseInt(String value) {
		try {
			return Integer.valueOf(value);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static List<Element> children(Element parent, String name) {
		List<Element> result = new ArrayList<>();
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node instanceof Element element && name.equals(element.getTagName())) {
				result.add(element);
			}
		}
		return result;
	}

}
