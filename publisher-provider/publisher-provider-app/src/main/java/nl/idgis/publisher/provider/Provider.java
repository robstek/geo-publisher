package nl.idgis.publisher.provider;

import nl.idgis.publisher.protocol.messages.Hello;
import nl.idgis.publisher.provider.protocol.GetDatasetInfo;
import nl.idgis.publisher.provider.protocol.GetVectorDataset;
import nl.idgis.publisher.provider.protocol.ListDatasetInfo;
import nl.idgis.publisher.provider.protocol.database.DescribeTable;
import nl.idgis.publisher.provider.protocol.database.FetchTable;
import nl.idgis.publisher.provider.protocol.database.PerformCount;
import nl.idgis.publisher.provider.protocol.metadata.GetAllMetadata;
import nl.idgis.publisher.provider.protocol.metadata.GetMetadata;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class Provider extends UntypedActor {
	
	private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	
	private final Props databaseProps, metadataProps;
	
	private ActorRef database, metadata;
	
	public Provider(Props databaseProps, Props metadataProps) {
		this.databaseProps = databaseProps;
		this.metadataProps = metadataProps;
	}
	
	public static Props props(Props databaseProps, Props metadataProps) {
		return Props.create(Provider.class, databaseProps, metadataProps);
	}
	
	@Override
	public void preStart() {
		database = getContext().actorOf(databaseProps, "database");
		metadata = getContext().actorOf(metadataProps, "metadata");
	}

	@Override
	public void onReceive(Object msg) throws Exception {
		if(msg instanceof Hello) {
			log.debug(msg.toString());
		} else if(msg instanceof ListDatasetInfo) {
			handleListDatasetInfo((ListDatasetInfo)msg);
		} else if(msg instanceof GetDatasetInfo) {
			handleGetDatasetInfo((GetDatasetInfo)msg);
		} else if(msg instanceof GetVectorDataset) {
			handleGetVectorDataset((GetVectorDataset)msg);
		} else if(msg instanceof GetAllMetadata) {
			metadata.forward(msg, getContext());
		} else if(msg instanceof GetMetadata) {
			metadata.forward(msg, getContext());
		} else if(msg instanceof DescribeTable) {
			database.forward(msg, getContext());
		} else if(msg instanceof FetchTable) {
			database.forward(msg, getContext());
		} else if(msg instanceof PerformCount) {
			database.forward(msg, getContext());
		} else {
			unhandled(msg);
		}
	}

	private void handleGetVectorDataset(GetVectorDataset msg) {
		log.debug("get vector dataset");		
		
		ActorRef fetcher = getContext().actorOf(VectorDatasetFetcher.props(getSender(), database, msg));
		metadata.tell(new GetMetadata(msg.getIdentification()), fetcher);
	}

	private void handleGetDatasetInfo(GetDatasetInfo msg) {
		log.debug("get dataset info");
		
		ActorRef builder = getContext().actorOf(DatasetInfoBuilder.props(getSender(), getSelf(), database, msg.getAttachmentTypes()));
		metadata.tell(new GetMetadata(msg.getIdentification()), builder);
	}

	private void handleListDatasetInfo(ListDatasetInfo msg) {
		log.debug("list dataset info");
		
		ActorRef converter = getContext().actorOf(DatasetInfoConverter.props(msg.getAttachmentTypes(), metadata, database));		
		converter.forward(msg, getContext());
	}

}
