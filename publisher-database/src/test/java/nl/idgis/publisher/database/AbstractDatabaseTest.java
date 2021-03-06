package nl.idgis.publisher.database;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;

import org.junit.After;
import org.junit.Before;

import scala.concurrent.ExecutionContext;

import nl.idgis.publisher.database.ExtendedPostgresTemplates;
import nl.idgis.publisher.database.messages.CreateDataset;
import nl.idgis.publisher.database.messages.RegisterSourceDataset;
import nl.idgis.publisher.domain.service.Column;
import nl.idgis.publisher.domain.service.Dataset;
import nl.idgis.publisher.domain.service.Table;
import nl.idgis.publisher.domain.service.Type;
import nl.idgis.publisher.utils.JdbcUtils;
import nl.idgis.publisher.utils.SyncAskHelper;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;

import com.mysema.query.QueryMetadata;
import com.mysema.query.sql.Configuration;
import com.mysema.query.sql.RelationalPath;
import com.mysema.query.sql.SQLQuery;
import com.mysema.query.sql.dml.SQLDeleteClause;
import com.mysema.query.sql.dml.SQLInsertClause;
import com.mysema.query.sql.dml.SQLUpdateClause;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import static nl.idgis.publisher.database.QDataSource.dataSource;

public abstract class AbstractDatabaseTest {
	
	private static final File BASE_DIR = new File("target/test-database");
		
	private ExtendedPostgresTemplates templates;	
	private Connection connection;	
	
	protected ActorSystem system;
	
	protected ActorRef database;
	
	protected SyncAskHelper sync;
	
	@Before
	public void database() throws Exception {
		File dbDir;
		do {
			dbDir = new File(BASE_DIR, "" + Math.abs(new Random().nextInt()));
		} while(dbDir.exists());
		
		String url = "jdbc:h2:" + dbDir.getAbsolutePath() + "/publisher;DATABASE_TO_UPPER=false;MODE=PostgreSQL;DB_CLOSE_ON_EXIT=false";		
		String user = "sa";
		String password = "";
		
		connection = DriverManager.getConnection(url, user, password);
		Statement stmt = connection.createStatement();
		
		// create database
		JdbcUtils.runRev(stmt, "nl/idgis/publisher/database");
		
		// adjust sequence start values to ensure that sequences do not 
		// provide the same values during the tests and potentially
		// masking relational integrity issues
		ResultSet rs = stmt.executeQuery("select SEQUENCE_SCHEMA || '.' || SEQUENCE_NAME from INFORMATION_SCHEMA.SEQUENCES");
		int sequenceValue = 1;
		while(rs.next()) {
			Statement seqStmt = connection.createStatement();			
			seqStmt.executeUpdate("alter sequence " + rs.getString(1) + " restart with " + sequenceValue);
			sequenceValue += 1000;
			seqStmt.close();
		}
		rs.close();
		
		stmt.close();
		
		templates = new ExtendedPostgresTemplates();
		
		Config databaseConfig = ConfigFactory.empty()
			.withValue("url", ConfigValueFactory.fromAnyRef(url))
			.withValue("templates", ConfigValueFactory.fromAnyRef("nl.idgis.publisher.database.ExtendedPostgresTemplates"))
			.withValue("user", ConfigValueFactory.fromAnyRef(user))
			.withValue("password", ConfigValueFactory.fromAnyRef(password));		
		
		Config akkaConfig = ConfigFactory.empty()
			.withValue("akka.loggers", ConfigValueFactory.fromIterable(Arrays.asList("akka.event.slf4j.Slf4jLogger")))
			.withValue("akka.loglevel", ConfigValueFactory.fromAnyRef("DEBUG"));
		
		system = ActorSystem.create("test", akkaConfig);
		database = actorOf(PublisherDatabase.props(databaseConfig), "database");
		
		sync = new SyncAskHelper(system);
	}
	
	protected ActorRef actorOf(Props props, String name) {
		return system.actorOf(props, name);
	}
	
	protected SQLInsertClause insert(RelationalPath<?> entity) {
		return new SQLInsertClause(connection, templates, entity);
	}
	
	protected SQLUpdateClause update(RelationalPath<?> entity) {
		return new SQLUpdateClause(connection, templates, entity);
	}
	
	protected SQLDeleteClause delete(RelationalPath<?> entity) {
		return new SQLDeleteClause(connection, templates, entity);
	}
	
	protected SQLQuery query() {
		return new SQLQuery(connection, templates);
	}
	
	protected SQLQuery query(QueryMetadata metadata) {
		return new SQLQuery(connection, new Configuration(templates), metadata);
	}
	
	
	@After
	public void shutdown() throws Exception {
		connection.close();		
		system.shutdown();
	}
	
	protected int insertDataSource(String dataSourceId) {
		Integer retval = query().from(dataSource)
			.where(dataSource.identification.eq(dataSourceId))
			.singleResult(dataSource.id);
		
		if(retval != null) {
			return retval;
		}
		
		return insert(dataSource)
			.set(dataSource.identification, dataSourceId)
			.set(dataSource.name, "My Test DataSource")
			.executeWithKey(dataSource.id);
	}
	
	protected void insertDataset() throws Exception {
		insertDataset("testDataset");
	}
		
	protected void insertDataset(String datasetId) throws Exception {
		insertDataSource();
		
		Dataset testDataset = createTestDataset();
		sync.ask(database, new RegisterSourceDataset("testDataSource", testDataset));
		
		Table testTable = testDataset.getTable();
		sync.ask(database, new CreateDataset(
				datasetId, 
				"My Test Dataset", 
				testDataset.getId(), 
				testTable.getColumns(), 
				"{ \"expression\": null }"));
	}
	
	protected int insertDataSource() {
		return insertDataSource("testDataSource");
	}
	
	protected Dataset createTestDataset() {
		return createTestDataset("testSourceDataset");
	}

	protected Dataset createTestDataset(String id) {
		List<Column> columns = Arrays.asList(
				new Column("col0", Type.TEXT),
				new Column("col1", Type.NUMERIC));
		Table table = new Table("My Test Table", columns);
		
		Timestamp revision = new Timestamp(new Date().getTime());
		
		return new Dataset(id, "testCategory", table, revision);		
	}
	
	protected ExecutionContext dispatcher() {
		return system.dispatcher();
	}
}
