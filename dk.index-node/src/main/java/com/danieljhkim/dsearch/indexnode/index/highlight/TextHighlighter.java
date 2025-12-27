package com.danieljhkim.dsearch.indexnode.index.highlight;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryScorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.search.highlight.TextFragment;
import org.apache.lucene.search.highlight.Highlighter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implements text highlighting using Lucene's Highlighter.
 * Highlights query terms in content and title fields.
 */
public class TextHighlighter {

	private static final Logger LOGGER = Logger.getLogger(TextHighlighter.class.getName());

	private static final String DEFAULT_PRE_TAG = "<em>";
	private static final String DEFAULT_POST_TAG = "</em>";
	private static final int DEFAULT_MAX_FRAGMENT_SIZE = 200;
	private static final int DEFAULT_MAX_FRAGMENTS = 3;

	private final String preTag;
	private final String postTag;
	private final int maxFragmentSize;
	private final int maxFragments;
	private final Analyzer analyzer;

	public TextHighlighter() {
		this(DEFAULT_PRE_TAG, DEFAULT_POST_TAG, DEFAULT_MAX_FRAGMENT_SIZE, DEFAULT_MAX_FRAGMENTS);
	}

	public TextHighlighter(String preTag, String postTag, int maxFragmentSize, int maxFragments) {
		this.preTag = preTag;
		this.postTag = postTag;
		this.maxFragmentSize = maxFragmentSize;
		this.maxFragments = maxFragments;
		this.analyzer = new StandardAnalyzer();
	}

	/**
	 * Highlights query terms in the specified text fields.
	 *
	 * @param query
	 *            the Lucene query
	 * @param fieldContents
	 *            map of field name to field content
	 * @param fieldsToHighlight
	 *            set of field names to highlight
	 * @return map of field name to highlighted content
	 */
	public Map<String, String> highlight(Query query, Map<String, String> fieldContents,
			Set<String> fieldsToHighlight) {
		Map<String, String> highlightedFields = new HashMap<>();

		if (query == null || fieldContents == null || fieldContents.isEmpty()) {
			return highlightedFields;
		}

		SimpleHTMLFormatter formatter = new SimpleHTMLFormatter(preTag, postTag);
		QueryScorer scorer = new QueryScorer(query);
		Highlighter highlighter = new Highlighter(formatter, scorer);
		highlighter.setTextFragmenter(new org.apache.lucene.search.highlight.SimpleFragmenter(maxFragmentSize));

		for (String fieldName : fieldsToHighlight) {
			String content = fieldContents.get(fieldName);
			if (content == null || content.isEmpty()) {
				continue;
			}

			try {
				String highlighted = highlightField(highlighter, fieldName, content);
				if (highlighted != null && !highlighted.isEmpty()) {
					highlightedFields.put(fieldName, highlighted);
				}
			} catch (Exception e) {
				LOGGER.log(Level.WARNING, "Failed to highlight field: " + fieldName, e);
			}
		}

		return highlightedFields;
	}

	/**
	 * Highlights a single field's content.
	 */
	private String highlightField(Highlighter highlighter, String fieldName, String content)
			throws IOException, InvalidTokenOffsetsException {
		TextFragment[] fragments = highlighter.getBestTextFragments(
				analyzer.tokenStream(fieldName, content),
				content,
				false,
				maxFragments);

		if (fragments == null || fragments.length == 0) {
			return null;
		}

		StringBuilder sb = new StringBuilder();
		for (TextFragment fragment : fragments) {
			if (fragment != null && fragment.getScore() > 0) {
				if (!sb.isEmpty()) {
					sb.append("...");
				}
				sb.append(fragment.toString());
			}
		}

		return !sb.isEmpty() ? sb.toString() : null;
	}

	/**
	 * Highlights query terms in a single field.
	 *
	 * @param query
	 *            the Lucene query
	 * @param fieldName
	 *            the field name
	 * @param content
	 *            the field content
	 * @return the highlighted content, or null if no highlights
	 */
	public String highlightField(Query query, String fieldName, String content) {
		if (query == null || content == null || content.isEmpty()) {
			return null;
		}

		try {
			SimpleHTMLFormatter formatter = new SimpleHTMLFormatter(preTag, postTag);
			QueryScorer scorer = new QueryScorer(query, fieldName);
			Highlighter highlighter = new Highlighter(formatter, scorer);
			highlighter.setTextFragmenter(new org.apache.lucene.search.highlight.SimpleFragmenter(maxFragmentSize));

			return highlightField(highlighter, fieldName, content);
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, "Failed to highlight field: " + fieldName, e);
			return null;
		}
	}
}
