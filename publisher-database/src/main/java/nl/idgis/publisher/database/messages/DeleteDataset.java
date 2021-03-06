package nl.idgis.publisher.database.messages;

public class DeleteDataset extends Query {

	private static final long serialVersionUID = 8201608118972323489L;
	
	private final String id;

	public DeleteDataset(String id) {
		super();
		this.id = id;
	}

	public String getId() {
		return id;
	}
	
	@Override
	public String toString() {
		return "GetDatasetInfo [Id=" + id + "]";
	}
	
}
