package run;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.MissingOptionException;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.apache.spark.sql.SparkSession;

import executor.Executor;
import extVp.DatabaseStatistics;
import joinTree.JoinTree;
import translator.Stats;
import translator.Translator;

/**
 * The Main class parses the CLI arguments and calls the translator and the executor.
 *
 * @author Matteo Cossu
 */
public class Main {

	private static final Logger logger = Logger.getLogger("PRoST");

	private static String inputFile;
	private static String outputFile;
	private static String statsFileName = "";
	private static String database_name;
	private static int treeWidth = -1;
	private static boolean useOnlyVP = false;
	private static int setGroupSize = -1;
	private static boolean benchmarkMode = false;
	private static String benchmark_file;
	private static String loj4jFileName = "log4j.properties";
	private static boolean useExtVP = false;
	private static long extVPMaximumSize = 25000; // 25000=~5gb

	private static DatabaseStatistics extVPDatabaseStatistics;
	private static String extVPDatabaseName;

	public static void main(final String[] args) throws IOException {
		final InputStream inStream = Main.class.getClassLoader().getResourceAsStream(loj4jFileName);
		final Properties props = new Properties();
		props.load(inStream);
		PropertyConfigurator.configure(props);

		/*
		 * Manage the CLI options
		 */
		final CommandLineParser parser = new PosixParser();
		final Options options = new Options();
		final Option inputOpt = new Option("i", "input", true, "Input file with the SPARQL query.");
		inputOpt.setRequired(true);
		options.addOption(inputOpt);
		final Option outputOpt = new Option("o", "output", true, "Path for the results in HDFS.");
		options.addOption(outputOpt);
		final Option statOpt = new Option("s", "stats", true, "File with statistics (required)");
		options.addOption(statOpt);
		statOpt.setRequired(true);
		final Option databaseOpt = new Option("d", "DB", true, "Database containing the VP tables and the PT.");
		databaseOpt.setRequired(true);
		options.addOption(databaseOpt);
		final Option helpOpt = new Option("h", "help", true, "Print this help.");
		options.addOption(helpOpt);
		final Option widthOpt = new Option("w", "width", true, "The maximum Tree width");
		options.addOption(widthOpt);
		final Option propertyTableOpt = new Option("v", "only_vp", false, "Use only Vertical Partitioning");
		options.addOption(propertyTableOpt);
		final Option benchmarkOpt = new Option("t", "times", true, "Save the time results in a csv file.");
		options.addOption(benchmarkOpt);
		final Option groupsizeOpt = new Option("g", "groupsize", true, "Minimum Group Size for Property Table nodes");
		options.addOption(groupsizeOpt);
		final Option extVPOpt = new Option("e", "extVP", false, "Uses extVP");
		options.addOption(extVPOpt);
		final Option extVPMaximumSizeOpt =
				new Option("extvpsize", "extVPSize", false, "Maximum size of ExtVP database");
		options.addOption(extVPMaximumSizeOpt);

		// TODO add option for minimum extvp selectivity

		final HelpFormatter formatter = new HelpFormatter();
		CommandLine cmd = null;
		try {
			cmd = parser.parse(options, args);
		} catch (final MissingOptionException e) {
			formatter.printHelp("JAR", "Execute a  SPARQL query with Spark", options, "", true);
			return;
		} catch (final ParseException e) {
			e.printStackTrace();
		}

		if (cmd.hasOption("help")) {
			formatter.printHelp("JAR", "Execute a  SPARQL query with Spark", options, "", true);
			return;
		}
		if (cmd.hasOption("input")) {
			inputFile = cmd.getOptionValue("input");
		}
		if (cmd.hasOption("output")) {
			outputFile = cmd.getOptionValue("output");
			logger.info("Output file set to:" + outputFile);
		}
		if (cmd.hasOption("stats")) {
			statsFileName = cmd.getOptionValue("stats");
		}
		if (cmd.hasOption("width")) {
			treeWidth = Integer.valueOf(cmd.getOptionValue("width"));
			logger.info("Maximum tree width is set to " + String.valueOf(treeWidth));
		}
		if (cmd.hasOption("only_vp")) {
			useOnlyVP = true;
			logger.info("Using Vertical Partitioning only.");
		}
		if (cmd.hasOption("groupsize")) {
			setGroupSize = Integer.valueOf(cmd.getOptionValue("groupsize"));
			logger.info("Minimum Group Size set to " + String.valueOf(setGroupSize));
		}
		if (cmd.hasOption("DB")) {
			database_name = cmd.getOptionValue("DB");
			extVPDatabaseName = "extVP_" + database_name;
		}
		if (cmd.hasOption("times")) {
			benchmarkMode = true;
			benchmark_file = cmd.getOptionValue("times");
		}
		if (cmd.hasOption("extVP")) {
			useExtVP = true;
			logger.info("Using extVP");
		}
		if (cmd.hasOption("extVPSize")) {
			extVPMaximumSize = Long.valueOf(cmd.getOptionValue("extVPSize"));
			logger.info("ExtVP maximum size set to " + extVPMaximumSize);
		}

		// initializes ExtVP database statistics
		extVPDatabaseStatistics = new DatabaseStatistics(extVPDatabaseName);
		extVPDatabaseStatistics = DatabaseStatistics.loadStatisticsFile(extVPDatabaseName, extVPDatabaseStatistics);

		// initialize the Spark environment
		final SparkSession spark = SparkSession.builder().appName("PRoST-Query").getOrCreate();
		// SQLContext sqlContext = spark.sqlContext();

		// create a singleton parsing a file with statistics
		Stats.getInstance().parseStats(statsFileName);

		final File file = new File(inputFile);

		// single file
		if (file.isFile()) {
			// translation phase
			final JoinTree translatedQuery = translateSingleQuery(inputFile, treeWidth);
			// System.out.println("****************************************************");
			// System.out.println(translatedQuery);
			// System.out.println("****************************************************");

			// execution phase
			final Executor executor = new Executor(translatedQuery, database_name);
			if (outputFile != null) {
				executor.setOutputFile(outputFile);
			}
			executor.execute();

			extVPDatabaseStatistics.clearCache(extVPMaximumSize / 2, extVPMaximumSize, spark);
			DatabaseStatistics.saveStatisticsFile(extVPDatabaseName, extVPDatabaseStatistics);
		} else if (file.isDirectory()) { // set of queries
			// empty executor to initialize Spark
			final Executor executor = new Executor(null, database_name);

			if (benchmarkMode) {
				// executor.cacheTables();
				executeBatch(random_sample(file.list(), 3), executor, spark);
				executor.clearQueryTimes();
			}

			// if the path is a directory execute every files inside
			executeBatch(file.list(), executor, spark);

			if (benchmarkMode) {
				executor.saveResultsCsv(benchmark_file);
			}

			DatabaseStatistics.saveStatisticsFile(extVPDatabaseName, extVPDatabaseStatistics);

		} else {
			logger.error("The input file is not set correctly or contains errors");
			return;
		}
	}

	private static JoinTree translateSingleQuery(final String query, final int width) {
		final Translator translator =
				new Translator(query, width, database_name, extVPDatabaseName, extVPDatabaseStatistics);
		if (!useOnlyVP) {
			translator.setPropertyTable(true);
		}
		translator.setUseExtVP(useExtVP);
		if (setGroupSize != -1) {
			translator.setMinimumGroupSize(setGroupSize);
		}
		return translator.translateQuery();
	}

	private static void executeBatch(final String[] queries, final Executor executor, final SparkSession spark) {
		for (final String fname : queries) {
			logger.info("Starting: " + fname);

			// translation phase
			final JoinTree translatedQuery = translateSingleQuery(inputFile + "/" + fname, treeWidth);

			// execution phase
			executor.setQueryTree(translatedQuery);
			executor.execute();
			extVPDatabaseStatistics.clearCache(extVPMaximumSize / 2, extVPMaximumSize, spark);
		}
	}

	private static String[] random_sample(final String[] queries, final int k) {
		final String[] sample = new String[k];
		for (int i = 0; i < sample.length; i++) {
			final int randomIndex = ThreadLocalRandom.current().nextInt(0, queries.length);
			sample[i] = queries[randomIndex];
		}
		return sample;
	}

}
