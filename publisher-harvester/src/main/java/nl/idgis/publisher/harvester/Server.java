package nl.idgis.publisher.harvester;

import java.net.InetSocketAddress;
import java.util.Map;

import nl.idgis.publisher.protocol.ConnectionHandler;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.io.Tcp;
import akka.io.Tcp.CommandFailed;
import akka.io.Tcp.Connected;
import akka.io.TcpMessage;

public class Server extends UntypedActor {

	private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	private final ActorRef listener;
	private final Map<String, ActorRef> targets;
	
	public Server(ActorRef listener, Map<String, ActorRef> targets) {
		this.listener = listener;
		this.targets = targets;
	}
	
	@Override
	public void preStart() {
		final ActorRef tcp = Tcp.get(getContext().system()).manager();
		tcp.tell(TcpMessage.bind(getSelf(), new InetSocketAddress(2014), 100), getSelf());
	}

	@Override
	public void onReceive(final Object msg) throws Exception {
		if (msg instanceof CommandFailed) {
			log.error(msg.toString());
			
			getContext().stop(getSelf());
		} else if (msg instanceof Connected) {
			log.debug("client connected");
			
			Props handlerProps = Props.create(ConnectionHandler.class, getSender(), listener, targets);
			final ActorRef handler = getContext().actorOf(handlerProps);
			
			getSender().tell(TcpMessage.register(handler), getSelf());
		} else {
			unhandled(msg);
		}
	}
}