package nl.idgis.publisher.provider.database;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import oracle.sql.STRUCT;

import org.deegree.geometry.io.WKBWriter;
import org.deegree.sqldialect.oracle.sdo.SDOGeometryConverter;

import nl.idgis.publisher.provider.protocol.Record;
import nl.idgis.publisher.provider.protocol.Records;
import nl.idgis.publisher.provider.protocol.UnsupportedType;
import nl.idgis.publisher.provider.protocol.WKBGeometry;
import nl.idgis.publisher.stream.StreamCursor;

import scala.concurrent.Future;

import akka.actor.Props;
import akka.dispatch.Futures;
import akka.event.Logging;
import akka.event.LoggingAdapter;

@SuppressWarnings("deprecation")
public class DatabaseCursor extends StreamCursor<ResultSet, Records> {
	
	private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	
	private final SDOGeometryConverter converter = new SDOGeometryConverter();
	
	private final int messageSize;

	public DatabaseCursor(ResultSet t, int messageSize) {
		super(t);
		
		this.messageSize = messageSize;
	}
	
	public static Props props(ResultSet t, int messageSize) {
		return Props.create(DatabaseCursor.class, t, messageSize);
	}
	
	private Object convert(Object value) throws Exception {
		if(value == null 
				|| value instanceof String
				|| value instanceof Number) {
			
			return value;
		} else if(value instanceof STRUCT) {
			return new WKBGeometry(WKBWriter.write(converter.toGeometry((STRUCT) value, null)));
		}
		
		return new UnsupportedType(value.getClass().getCanonicalName());
	}
	
	private Record toRecord() throws Exception {
		int columnCount = t.getMetaData().getColumnCount();
		
		List<Object> values = new ArrayList<>();
		for(int j = 0; j < columnCount; j++) {
			Object o = t.getObject(j + 1);
			values.add(convert(o));
		}
		
		return new Record(values);
	}

	@Override
	protected Future<Records> next() {
		log.debug("fetching next records");
		
		try {
			List<Record> records = new ArrayList<>();
			records.add(toRecord());
			
			for(int i = 1; i < messageSize; i++) {
				if(!t.next()) {
					break;
				}
				
				records.add(toRecord());
			}
			
			return Futures.successful(new Records(records));
		} catch(Throwable t) {
			log.error(t, "failed to fetch records");			
			return Futures.failed(t);
		}
	}

	@Override
	protected boolean hasNext() throws Exception {		
		return t.next();
	}
	
	@Override
	public void postStop() throws SQLException {
		t.getStatement().close();
		t.close();
	}
}
