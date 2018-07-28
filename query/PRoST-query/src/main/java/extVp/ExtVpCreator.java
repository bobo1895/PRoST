package extVp;

import java.util.List;
import java.util.ListIterator;
import org.apache.log4j.Logger;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.shared.PrefixMapping;

import translator.Stats;

/**
 * Class for the creation and maintenance of ExtVP database and tables
 *
 */
public class ExtVpCreator {
	private static final Logger logger = Logger.getLogger("PRoST");
	
	public enum extVPType {
	    SS, SO, OS, OO
	}
	
	/**
	 * Creates a ExtVP table.
	 * 
	 * <p>Creates a ExtVP table for the given predicates, only if they do not exists in the database. 
	 * Creates a new ExtVP database if it does not exists already.
	 * Updates the given ExtVP database statistics object </p
	 * 
	 * 
	 * @param predicate1
	 * @param predicate2
	 * @param extVPType  Type of the ExtVP table to be created.
	 * @param spark
	 * @param databaseStatistics
	 * @param extVPDatabaseName
	 */
	public void createExtVPTable(String predicate1, String predicate2, extVPType extVPType, SparkSession spark, 
			DatabaseStatistics databaseStatistics, String extVPDatabaseName) {
		String vp1TableName = "vp_" + Stats.getInstance().findTableName(predicate1);
		String vp2TableName = "vp_" + Stats.getInstance().findTableName(predicate2);
		String extVpTableName = getExtVPTableName(predicate1, predicate2, extVPType);
		
		String tableNameWithDatabaseIdentifier = extVPDatabaseName + "." + extVpTableName;
		
		spark.sql("CREATE DATABASE IF NOT EXISTS " + extVPDatabaseName);
				
		if (!spark.catalog().tableExists(tableNameWithDatabaseIdentifier)) {
			String createTableQuery = String.format("create table if not exists %1$s(s string, o string) stored as parquet", 
					tableNameWithDatabaseIdentifier);
			
			spark.sql(createTableQuery);
							
			String queryOnCommand = "";	
			switch (extVPType) {
				case SO:
					queryOnCommand = String.format("%1$s.s=%2$s.o", vp1TableName, vp2TableName);
					break;
				case OS:
					queryOnCommand = String.format("%1$s.o=%2$s.s", vp1TableName, vp2TableName);
					break;
				case OO: 
					queryOnCommand = String.format("%1$s.o=%2$s.o", vp1TableName, vp2TableName);
					break;
				case SS:
				default:
					queryOnCommand = String.format("%1$s.s=%2$s.s", vp1TableName, vp2TableName);
					break;
			}				
			String insertDataQuery = String.format("insert overwrite table %1$s select %2$s.s as "
					+ "s, %2$s.o as o from %2$s left semi join %3$s on %4$s", 
					tableNameWithDatabaseIdentifier, vp1TableName, vp2TableName, queryOnCommand);
			spark.sql(insertDataQuery);
			
			logger.info("ExtVP: " + tableNameWithDatabaseIdentifier + " created.");
			logger.info("tablename: " + extVpTableName);
			
			
			//TODO Check if table statistics already exists in the database statistics. Update it if it exists. Do not search for table sizes if they have already been calculates (statistic exists)
			//calculates table size and selectivity
			//Protobuff stats for VP tables size are not correctly saved, therefore we check the size directly from the hive table
			Dataset<Row> vpDF = spark.sql("SELECT * FROM " + vp1TableName);			
			Long vpTableSize = vpDF.count();
			
			Dataset<Row> extVPDF = spark.sql("SELECT * FROM " + tableNameWithDatabaseIdentifier);	
			Long extVPSize = extVPDF.count();
			
			logger.info("size: " + extVPSize + " - selectivity: " + (float)extVPSize/(float)vpTableSize + " vp table size: " + vpTableSize);
			
			databaseStatistics.getTables().put(extVpTableName, new TableStatistic(extVpTableName, (float)extVPSize/(float)vpTableSize, extVPSize));	
			databaseStatistics.setSize(databaseStatistics.getSize() + extVPSize);
		}
	}
	
	/**
	 * Creates possible ExtVP tables for the give triples list
	 * 
	 * <p>Given the list of triples of a query, create all possible extVP tables if they have not been created before</p>
	 * 
	 * @param triples
	 * @param prefixes
	 * @param spark
	 * @param databaseStatistic
	 * @param extVPDatabaseName
	 */
	public void createExtVPFromTriples(List<Triple> triples, PrefixMapping prefixes, SparkSession spark, DatabaseStatistics databaseStatistic, String extVPDatabaseName){
		for(ListIterator<Triple> outerTriplesListIterator = triples.listIterator(); outerTriplesListIterator.hasNext() ; ) {
		    Triple outerTriple = outerTriplesListIterator.next();
		    String outerSubject = outerTriple.getSubject().toString(prefixes);
		    String outerPredicate = outerTriple.getPredicate().toString(prefixes);
		    String outerObject = outerTriple.getObject().toString(prefixes);
		    
		    for(ListIterator<Triple> innerTriplesListIterator = triples.listIterator(outerTriplesListIterator.nextIndex()); innerTriplesListIterator.hasNext(); ) {
		    	Triple innerTriple = innerTriplesListIterator.next();
		    	String innerSubject = innerTriple.getSubject().toString(prefixes);
			    String innerPredicate = innerTriple.getPredicate().toString(prefixes);
			    String innerObject = innerTriple.getObject().toString(prefixes);
			    
			    //TODO Check if table exists in the statistics. If it exists, do not create it again
			    
			    if (outerTriple.getSubject().isVariable() && innerTriple.getSubject().isVariable() && outerSubject.equals(innerSubject)) {
			    	//SS
			    	createExtVPTable(outerPredicate, innerPredicate, extVPType.SS, spark, databaseStatistic, extVPDatabaseName);
				    createExtVPTable(innerPredicate, outerPredicate, extVPType.SS, spark, databaseStatistic, extVPDatabaseName); 
			    }
			    if (outerTriple.getObject().isVariable() && innerTriple.getObject().isVariable() &&  outerObject.equals(innerObject)) {
			    	//OO
			    	createExtVPTable(outerPredicate, innerPredicate, extVPType.OO, spark, databaseStatistic, extVPDatabaseName);
				    createExtVPTable(innerPredicate, outerPredicate, extVPType.OO, spark, databaseStatistic, extVPDatabaseName); 
			    }
			    if (outerTriple.getObject().isVariable() && innerTriple.getSubject().isVariable() && outerObject.equals(innerSubject)) {
			    	//OS
			    	createExtVPTable(outerPredicate, innerPredicate, extVPType.OS, spark, databaseStatistic, extVPDatabaseName);
				    createExtVPTable(innerPredicate, outerPredicate, extVPType.SO, spark, databaseStatistic, extVPDatabaseName); 
			    }
			    if (outerTriple.getSubject().isVariable() && innerTriple.getObject().isVariable() && outerSubject.equals(innerObject)) {
			    	//SO
			    	createExtVPTable(outerPredicate, innerPredicate, extVPType.SO, spark, databaseStatistic, extVPDatabaseName);
				    createExtVPTable(innerPredicate, outerPredicate, extVPType.OS, spark, databaseStatistic, extVPDatabaseName); 
			    } 
		    }
		   
		}
	}
	
	public static String getValidHiveName(String columnName) {
		return columnName.replaceAll("[<>]", "").trim().replaceAll("[[^\\w]+]", "_");
	}
	
	/**
	 * Creates a ExtVP table name for the given predicates and join type
	 * 
	 * <p>Format of generated table name is extVP_<i>type</i>_<i>predicate1</i>__<i>predicate2</i>
	 * 
	 * @param predicate1
	 * @param predicate2
	 * @param type Join type
	 * @return name of ExtVP table
	 */
	public static String getExtVPTableName(String predicate1, String predicate2, extVPType type) {	
		String extVpTableName = "extVP_" + type.toString() + "_" + getValidHiveName(predicate1) + "__" + getValidHiveName(predicate2);
		
		return extVpTableName;
	}
	
	//TODO function to insert intermediate data in a table (receives Dataset<Row>
}
