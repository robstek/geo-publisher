package nl.idgis.publisher.domain.job;

import nl.idgis.publisher.domain.MessageProperties;
import nl.idgis.publisher.domain.MessageType;
import nl.idgis.publisher.domain.job.harvest.HarvestLogType;

public enum JobType implements MessageType<MessageProperties> {
	HARVEST(HarvestLogType.class), IMPORT, SERVICE;

	private final Class<? extends MessageType<?>> logMessageEnum;

	private JobType() {
		this(null);
	}
	
	private JobType(Class<? extends MessageType<?>> logMessageEnum) {
		this.logMessageEnum = logMessageEnum;
	}
	
	@Override
	public Class<? extends MessageProperties> getContentClass() {
		return null;
	}
	
	public Class<? extends MessageType<?>> getLogMessageEnum () {
		return logMessageEnum;
	}
}