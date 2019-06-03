package joinTree;

import java.util.ArrayList;
import java.util.List;

import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.shared.PrefixMapping;
import org.apache.spark.sql.SQLContext;
import stats.DatabaseStatistics;
import stats.PropertyStatistics;
import translator.triplesGroup.TriplesGroup;
import utils.Utils;

/**
 * A node that uses a Joined Wide Property Table.
 */
public class JWPTNode extends MVNode {
	private static final String COLUMN_NAME_COMMON_RESOURCE = "r";
	private static final String WPT_PREFIX = "o_";
	private static final String IWPT_PREFIX = "s_";
	private final List<TriplePattern> wptTripleGroup;
	private final List<TriplePattern> iwptTripleGroup;
	private String JOINED_TABLE_NAME = "joined_wide_property_table_outer";

	public JWPTNode(final TriplesGroup triplesGroup, final PrefixMapping prefixes,
					final DatabaseStatistics statistics) {

		super(statistics);

		wptTripleGroup = new ArrayList<>();
		for (final Triple t : triplesGroup.getForwardTriples()) {
			final TriplePattern tp = new TriplePattern(t, prefixes);
			wptTripleGroup.add(tp);
			this.addTriplePattern(tp);
		}

		iwptTripleGroup = new ArrayList<>();
		for (final Triple t : triplesGroup.getInverseTriples()) {
			final TriplePattern tp = new TriplePattern(t, prefixes);
			iwptTripleGroup.add(tp);
			this.addTriplePattern(tp);
		}
		setIsComplex();
	}

	public JWPTNode(final Triple triple, final PrefixMapping prefixes,
					final DatabaseStatistics statistics, final boolean tripleAsForwardGroup) {

		super(statistics);
		if (tripleAsForwardGroup) {
			wptTripleGroup = new ArrayList<>();
			final TriplePattern triplePattern = new TriplePattern(triple, prefixes);
			wptTripleGroup.add(triplePattern);
			this.addTriplePattern(triplePattern);

			iwptTripleGroup = new ArrayList<>();
		} else {
			iwptTripleGroup = new ArrayList<>();
			final TriplePattern triplePattern = new TriplePattern(triple, prefixes);
			iwptTripleGroup.add(triplePattern);
			this.addTriplePattern(triplePattern);

			wptTripleGroup = new ArrayList<>();
		}

		setIsComplex();
	}

	/**
	 * Uses the database statistics to determine if the column in the JWPT for each
	 * <code>TriplePattern</code> in <code>wptTripleGroup</code> and
	 * <code>iwptTripleGroup</code>is complex.
	 */
	private void setIsComplex() {
		for (final TriplePattern triplePattern : wptTripleGroup) {
			triplePattern.setComplex(
					this.getStatistics().getProperties().get(triplePattern.getPredicate()).isComplex());
		}

		for (final TriplePattern triplePattern : iwptTripleGroup) {
			triplePattern.setComplex(
					this.getStatistics().getProperties().get(triplePattern.getPredicate()).isInverseComplex());
		}
	}

	@Override
	public void computeNodeData(final SQLContext sqlContext) {
		final TriplePattern triple = this.getFirstTriplePattern();
		if (this.size() == 1 && triple.getPredicateType().equals(ElementType.VARIABLE)) {
			if (triple.getSubjectType().equals(ElementType.CONSTANT) || triple.getObjectType().equals(ElementType.VARIABLE)) {
				computeForwardVariablePredicateNodeData(sqlContext);
			} else {
				computeInverseVariablePredicateNodeData(sqlContext);
			}
		} else {
			computeConstantPredicateNodeData(sqlContext);
		}
	}

	private void computeConstantPredicateNodeData(final SQLContext sqlContext) {
		final ArrayList<String> whereElements = new ArrayList<>();
		final ArrayList<String> selectElements = new ArrayList<>();
		final ArrayList<String> explodedElements = new ArrayList<>();

		// subject
		if (!wptTripleGroup.isEmpty()) {
			if (wptTripleGroup.get(0).getSubjectType() == ElementType.VARIABLE) {
				selectElements.add(COLUMN_NAME_COMMON_RESOURCE + " AS "
						+ Utils.removeQuestionMark(wptTripleGroup.get(0).getSubject()));
			} else {
				whereElements.add(COLUMN_NAME_COMMON_RESOURCE + "='" + wptTripleGroup.get(0).getSubject() + "'");
			}
		} else if (!iwptTripleGroup.isEmpty()) {
			if (iwptTripleGroup.get(0).getObjectType() == ElementType.VARIABLE) {
				selectElements.add(COLUMN_NAME_COMMON_RESOURCE + " AS "
						+ Utils.removeQuestionMark(iwptTripleGroup.get(0).getObject()));
			} else {
				whereElements.add(COLUMN_NAME_COMMON_RESOURCE + "='" + iwptTripleGroup.get(0).getObject() + "'");
			}
		}

		// forward patterns
		for (final TriplePattern t : wptTripleGroup) {
			final String columnName =
					WPT_PREFIX.concat(this.getStatistics().getProperties().get(t.getPredicate()).getInternalName());
			assert !columnName.equals(WPT_PREFIX) : "This column does not exists: " + columnName;

			if (t.getObjectType() == ElementType.CONSTANT) {
				if (t.isComplex()) {
					whereElements.add("array_contains(" + columnName + ", '" + t.getObject() + "')");
				} else {
					whereElements.add(columnName + "='" + t.getObject() + "'");
				}
			} else if (t.isComplex()) {
				selectElements.add(" P" + columnName + " AS " + Utils.removeQuestionMark(t.getObject()));
				explodedElements.add("\n lateral view explode(" + columnName + ") exploded" + columnName
						+ " AS P" + columnName);
			} else {
				selectElements.add(columnName + " AS " + Utils.removeQuestionMark(t.getObject()));
				whereElements.add(columnName + " IS NOT NULL");
			}
		}

		// inverse patterns
		for (final TriplePattern t : iwptTripleGroup) {
			final String columnName = IWPT_PREFIX.concat(
					this.getStatistics().getProperties().get(t.getPredicate()).getInternalName());
			assert !columnName.equals(IWPT_PREFIX) : "This column does not exists: " + columnName;

			if (t.getSubjectType() == ElementType.CONSTANT) {
				if (t.isComplex()) {
					whereElements.add("array_contains(" + columnName + ", '" + t.getSubject() + "')");
				} else {
					whereElements.add(columnName + "='" + t.getSubject() + "'");
				}
			} else if (t.isComplex()) {
				selectElements.add(" P" + columnName + " AS " + Utils.removeQuestionMark(t.getSubject()));
				explodedElements.add("\n lateral view explode(" + columnName + ") exploded" + columnName
						+ " AS P" + columnName);
			} else {
				selectElements.add(columnName + " AS " + Utils.removeQuestionMark(t.getSubject()));
				whereElements.add(columnName + " IS NOT NULL");
			}
		}

		String query = "SELECT " + String.join(",", selectElements);
		query += " FROM " + JOINED_TABLE_NAME;
		if (!explodedElements.isEmpty()) {
			query += " " + String.join(" ", explodedElements);
		}
		if (!whereElements.isEmpty()) {
			query += " WHERE " + String.join(" AND ", whereElements);
		}

		this.setSparkNodeData(sqlContext.sql(query));
	}

	//assumes a single pattern in the triples groups, uses the forward part of the table
	private void computeForwardVariablePredicateNodeData(final SQLContext sqlContext) {
		assert this.size() == 1 : "JWPT nodes with variable predicates can only contain one triple pattern";

		final List<String> properties = new ArrayList<>();
		for (final PropertyStatistics propertyStatistics : this.getStatistics().getProperties().values()) {
			properties.add(propertyStatistics.getInternalName());
		}
		final TriplePattern triple = this.getFirstTriplePattern();

		for (final String property : properties) {
			final ArrayList<String> selectElements = new ArrayList<>();
			final ArrayList<String> whereElements = new ArrayList<>();
			final ArrayList<String> explodedElements = new ArrayList<>();

			if (triple.getSubjectType() == ElementType.VARIABLE) {
				selectElements.add(COLUMN_NAME_COMMON_RESOURCE + " AS "
						+ Utils.removeQuestionMark(triple.getSubject()));
			} else {
				whereElements.add(COLUMN_NAME_COMMON_RESOURCE + "='" + triple.getSubject() + "'");
			}

			selectElements.add("'" + property + "' as " + Utils.removeQuestionMark(triple.getPredicate()));

			final String columnName = WPT_PREFIX.concat(property);

			if (triple.getObjectType() == ElementType.CONSTANT) {
				if (triple.isComplex()) {
					whereElements.add("array_contains(" + columnName + ", '" + triple.getObject() + "')");
				} else {
					whereElements.add(columnName + "='" + triple.getObject() + "'");
				}
			} else if (triple.isComplex()) {
				selectElements.add(" P" + columnName + " AS " + Utils.removeQuestionMark(triple.getObject()));
				explodedElements.add("\n lateral view explode(" + columnName + ") exploded" + columnName
						+ " AS P" + columnName);
			} else {
				selectElements.add(columnName + " AS " + Utils.removeQuestionMark(triple.getObject()));
				whereElements.add(columnName + " IS NOT NULL");
			}

			String query = "SELECT " + String.join(",", selectElements);
			query += " FROM " + JOINED_TABLE_NAME;
			if (!explodedElements.isEmpty()) {
				query += " " + String.join(" ", explodedElements);
			}
			if (!whereElements.isEmpty()) {
				query += " WHERE " + String.join(" AND ", whereElements);
			}

			if (this.getSparkNodeData() == null) {
				this.setSparkNodeData(sqlContext.sql(query));
			} else {
				this.setSparkNodeData(this.getSparkNodeData().union(sqlContext.sql(query)));
			}
		}
	}

	//assumes a single pattern in the triples groups, uses the forward part of the table
	private void computeInverseVariablePredicateNodeData(final SQLContext sqlContext) {
		assert this.size() == 1 : "JWPT nodes with variable predicates can only contain one triple pattern";

		final List<String> properties = new ArrayList<>();
		for (final PropertyStatistics propertyStatistics : this.getStatistics().getProperties().values()) {
			properties.add(propertyStatistics.getInternalName());
		}
		final TriplePattern triple = this.getFirstTriplePattern();

		for (final String property : properties) {
			final ArrayList<String> selectElements = new ArrayList<>();
			final ArrayList<String> whereElements = new ArrayList<>();
			final ArrayList<String> explodedElements = new ArrayList<>();

			if (triple.getObjectType() == ElementType.VARIABLE) {
				selectElements.add(COLUMN_NAME_COMMON_RESOURCE + " AS "
						+ Utils.removeQuestionMark(triple.getObject()));
			} else {
				whereElements.add(COLUMN_NAME_COMMON_RESOURCE + "='" + triple.getObject() + "'");
			}

			selectElements.add("'" + property + "' as " + Utils.removeQuestionMark(triple.getPredicate()));

			final String columnName = IWPT_PREFIX.concat(property);

			if (triple.getSubjectType() == ElementType.CONSTANT) {
				if (triple.isComplex()) {
					whereElements.add("array_contains(" + columnName + ", '" + triple.getSubject() + "')");
				} else {
					whereElements.add(columnName + "='" + triple.getSubject() + "'");
				}
			} else if (triple.isComplex()) {
				selectElements.add(" P" + columnName + " AS ");
				explodedElements.add("\n lateral view explode(" + columnName + ") exploded" + columnName
						+ " AS P" + columnName);
			} else {
				selectElements.add(columnName + " AS " + Utils.removeQuestionMark(triple.getSubject()));
				whereElements.add(columnName + " IS NOT NULL");
			}

			String query = "SELECT " + String.join(",", selectElements);
			query += " FROM " + JOINED_TABLE_NAME;
			if (!explodedElements.isEmpty()) {
				query += " " + String.join(" ", explodedElements);
			}
			if (!whereElements.isEmpty()) {
				query += " WHERE " + String.join(" AND ", whereElements);
			}

			if (this.getSparkNodeData() == null) {
				this.setSparkNodeData(sqlContext.sql(query));
			} else {
				this.setSparkNodeData(this.getSparkNodeData().union(sqlContext.sql(query)));
			}
		}
	}

	@Override
	public String toString() {
		final StringBuilder str = new StringBuilder("{");
		str.append("JWPT node (").append(this.getPriority()).append("): ");
		for (final TriplePattern tpGroup : this.getTripleGroup()) {
			str.append(tpGroup.toString()).append(", ");
		}
		str.append(" }");
		return str.toString();
	}

}
