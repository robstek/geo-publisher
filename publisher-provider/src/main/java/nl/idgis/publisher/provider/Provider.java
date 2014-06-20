package nl.idgis.publisher.provider;

import java.util.HashMap;
import java.util.Map;

import nl.idgis.publisher.protocol.Hello;
import nl.idgis.publisher.protocol.Message;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.io.Tcp.Connected;
import akka.io.Tcp.ConnectionClosed;

public class Provider extends UntypedActor {
	
	private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	
	private Map<String, ActorRef> actors;
	
	@Override
	public void preStart() {
		actors = new HashMap<String, ActorRef>();
		actors.put("provider", getSelf());
		
		connect();
	}
	
	private void connect() {
		log.debug("connecting");		
		
		final Props clientProps = Props.create(Client.class, getSelf(), actors);		
		getContext().actorOf(clientProps, "client");
	}

	@Override
	public void onReceive(Object msg) throws Exception {
		if (msg instanceof Connected) {
			log.debug("connected");
			
			getSender().tell(new Message("harvester", new Hello("My data provider")), getSelf());			
		} else if (msg instanceof Hello) {
			log.debug(msg.toString());
		} else if (msg instanceof ConnectionClosed) {
			log.debug("disconnected");
			
			connect();
		} else {
			unhandled(msg);
		}
	}
}