package nl.idgis.publisher;

import java.io.File;
import java.util.concurrent.TimeUnit;

import scala.concurrent.duration.Duration;

import nl.idgis.publisher.admin.Admin;

import nl.idgis.publisher.database.GeometryDatabase;
import nl.idgis.publisher.database.PublisherDatabase;
import nl.idgis.publisher.database.messages.GetVersion;
import nl.idgis.publisher.database.messages.TerminateJobs;
import nl.idgis.publisher.database.messages.Version;

import nl.idgis.publisher.harvester.Harvester;
import nl.idgis.publisher.job.JobSystem;
import nl.idgis.publisher.loader.Loader;
import nl.idgis.publisher.messages.ActiveJobs;
import nl.idgis.publisher.messages.GetActiveJobs;
import nl.idgis.publisher.metadata.FileMetadataStore;
import nl.idgis.publisher.metadata.MetadataGenerator;
import nl.idgis.publisher.metadata.MetadataStore;
import nl.idgis.publisher.metadata.messages.GenerateMetadata;
import nl.idgis.publisher.monitor.messages.Tree;
import nl.idgis.publisher.protocol.messages.Ack;
import nl.idgis.publisher.service.Service;
import nl.idgis.publisher.utils.Boot;
import nl.idgis.publisher.utils.JdbcUtils;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Procedure;

import com.typesafe.config.Config;

public class ServiceApp extends UntypedActor {
	
	private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	
	private final Config config;
	
	private ActorRef database;
	
	public ServiceApp(Config config) {
		this.config = config;
	}
	
	public static Props props(Config config) {
		return Props.create(ServiceApp.class, config);
	}
	
	@Override
	public void preStart() throws Exception {
		log.info("starting service application");
		
		Config databaseConfig = config.getConfig("database");
		database = getContext().actorOf(PublisherDatabase.props(databaseConfig), "database");
		
		database.tell(new GetVersion(), getSelf());
		getContext().become(waitingForVersion());
	}
	
	@Override
	public void onReceive(Object msg) throws Exception {
		unhandled(msg);
	}
	
	private Procedure<Object> waitingForJobTermination() {
		log.debug("waiting for job termination");
		
		return new Procedure<Object>() {

			@Override
			public void apply(Object msg) throws Exception {
				if(msg instanceof Ack) {
					getContext().become(running());
				} else {
					unhandled(msg);
				}
			}
		};
	}
	
	private Procedure<Object> waitingForVersion() {
		log.debug("waiting for database version");
		
		return new Procedure<Object>() {

			@Override
			public void apply(Object msg) throws Exception {
				if(msg instanceof Version) {
					Version version = (Version)msg;
					
					log.info("database version: " + version);
					
					int versionId = version.getId();					
					int lastVersionId = JdbcUtils.maxRev("nl/idgis/publisher/database", versionId);
					
					if(versionId != lastVersionId) {
						log.error("database obsolete, expected version id: " + lastVersionId);
						getContext().stop(getSelf());
					} else {					
						database.tell(new TerminateJobs(), getSelf());
						getContext().become(waitingForJobTermination());
					}
				} else {
					unhandled(msg);
				}
			}
			
		};
	}
	
	private Procedure<Object> running() throws Exception {
		Config geometryDatabaseConfig = config.getConfig("geometry-database");
		final ActorRef geometryDatabase = getContext().actorOf(GeometryDatabase.props(geometryDatabaseConfig), "geometryDatabase");
		
		Config harvesterConfig = config.getConfig("harvester");
		final ActorRef harvester = getContext().actorOf(Harvester.props(database, harvesterConfig), "harvester");
		
		final ActorRef loader = getContext().actorOf(Loader.props(geometryDatabase, database, harvester), "loader");
		
		Config geoserverConfig = config.getConfig("geoserver");
		
		final ActorRef service = getContext().actorOf(Service.props(database, geoserverConfig, geometryDatabaseConfig), "service");
		
		ActorRef jobs = getContext().actorOf(JobSystem.props(database, harvester, loader, service), "jobs");
		
		getContext().actorOf(Admin.props(database, harvester, loader, service, jobs), "admin");
		
		Config metadataConfig = config.getConfig("metadata");
		
		MetadataStore serviceMetadataSource = new FileMetadataStore(new File(metadataConfig.getString("serviceSource")));
		MetadataStore datasetMetadataTarget = new FileMetadataStore(new File(metadataConfig.getString("datasetTarget")));
		MetadataStore serviceMetadataTarget = new FileMetadataStore(new File(metadataConfig.getString("serviceTarget")));		
		
		ActorRef metadataGenerator = getContext().actorOf(MetadataGenerator.props(database, service, harvester, serviceMetadataSource, datasetMetadataTarget, serviceMetadataTarget, metadataConfig.getConfig("generator-constants")), "metadata-generator");
		getContext().system().scheduler().schedule(
				Duration.create(10, TimeUnit.SECONDS), 
				Duration.create(10, TimeUnit.SECONDS),
				metadataGenerator, new GenerateMetadata(), 
				getContext().dispatcher(), getSelf());
		
		if(log.isDebugEnabled()) {
			ActorSystem system = getContext().system();
			system.scheduler().schedule(
					Duration.Zero(), 
					Duration.create(5, TimeUnit.SECONDS), 
					getSelf(), 
					new GetActiveJobs(), 
					getContext().dispatcher(), 
					getSelf());
		}
		
		log.info("service application started");
		
		return new Procedure<Object>() {

			@Override
			public void apply(Object msg) throws Exception {
				if(msg instanceof Tree) {
					log.debug(msg.toString());
				} else if(msg instanceof GetActiveJobs) {
					harvester.tell(msg, getSelf());
					loader.tell(msg, getSelf());
				} else if(msg instanceof ActiveJobs) {
					log.debug("active jobs: " + msg);
				} else {
					unhandled(msg);
				}
			}
		};
	}

	public static void main(String[] args) {
		Boot boot = Boot.init("service");
		boot.startApplication(ServiceApp.props(boot.getConfig()));
	}
}
