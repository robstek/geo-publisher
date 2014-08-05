package controllers;

import static models.Domain.from;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import models.Domain.Constant;
import models.Domain.Function;
import models.Domain.Function2;
import models.Domain.Function3;
import models.Domain.Function4;
import nl.idgis.publisher.domain.query.DomainQuery;
import nl.idgis.publisher.domain.query.ListColumns;
import nl.idgis.publisher.domain.query.ListDatasets;
import nl.idgis.publisher.domain.query.ListSourceDatasets;
import nl.idgis.publisher.domain.response.Page;
import nl.idgis.publisher.domain.service.Column;
import nl.idgis.publisher.domain.web.Category;
import nl.idgis.publisher.domain.web.DataSource;
import nl.idgis.publisher.domain.web.Dataset;
import nl.idgis.publisher.domain.web.PutDataset;
import nl.idgis.publisher.domain.web.SourceDataset;
import nl.idgis.publisher.domain.web.SourceDatasetStats;
import play.Logger;
import play.Play;
import play.data.Form;
import play.data.validation.Constraints;
import play.libs.Akka;
import play.libs.F.Promise;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;
import views.html.datasets.columns;
import views.html.datasets.form;
import views.html.datasets.list;
import actions.DefaultAuthenticator;
import actors.Database;
import akka.actor.ActorSelection;

@Security.Authenticated (DefaultAuthenticator.class)
public class Datasets extends Controller {
	private final static String databaseRef = Play.application().configuration().getString("publisher.database.actorRef");
	private static int counter = 0;

	public static Promise<Result> list (long page) {
		return listByCategoryAndMessages(null, false, page);
	}
	
	
	public static Promise<Result> listWithMessages (long page) {
		return listByCategoryAndMessages(null, true, page);
	}
	
	public static Promise<Result> listByCategory (String categoryId, long page) {
		return listByCategoryAndMessages(categoryId, false, page);
	}
	
	
	public static Promise<Result> listByCategoryWithMessages (String categoryId, long page) {
		return listByCategoryAndMessages(categoryId, true, page);
	}
	
	public static Promise<Result> delete(final String datasetId){
		System.out.println("delete dataset " + datasetId);
		final ActorSelection database = Akka.system().actorSelection (databaseRef);
		
		from(database).delete(Dataset.class, datasetId)
			.execute(new Function<Boolean, Result>() {

				@Override
				public Result apply(Boolean a) throws Throwable {
					// TODO Auto-generated method stub
					System.out.println("apply delete: " + a);
					return null;
				}
			});
 
		return list(1);
	}
	
	private static List<Column> makeColumnList(){
		List<Column> colList = new ArrayList<Column>();
		Column col = new Column("FirstColumn","INTEGER");
		colList.add(col);
		col = new Column("SecondColumn","STRING");
		colList.add(col);
		col = new Column("ThirdColumn","BOOLEAN");
		colList.add(col);
		col = new Column("RandomColumn-"+Math.random(),"SOMETYPE");
		colList.add(col);
		return colList;
	}
	
	public static Promise<Result> update(){
		// TODO construct putdataset from form (putdataset.id != null)
		PutDataset putDataset = new PutDataset("1", "MyUpdatedDataset" + (counter++), "SomeSourceDataset", makeColumnList());		
		System.out.println("update dataset " + putDataset);
		
		final ActorSelection database = Akka.system().actorSelection (databaseRef);
		
		from(database).put(putDataset); 
		
		return list(1);
	}
	
	public static Promise<Result>  create() {
		// TODO construct putdataset from form (putdataset.id == null)
		PutDataset putDataset = new PutDataset(null, "MyCreatedDataset" + (counter++), "SomeSourceDataset", makeColumnList());		
		System.out.println("create dataset " + putDataset);
		
		final ActorSelection database = Akka.system().actorSelection (databaseRef);

		from(database).put(putDataset); 
		
		return list(1);
	}

	private static DomainQuery<Page<SourceDatasetStats>> listSourceDatasets (final String dataSourceId, final String categoryId) {
		if (dataSourceId == null || categoryId == null || dataSourceId.isEmpty () || categoryId.isEmpty ()) {
			return new Constant<Page<SourceDatasetStats>> (new Page.Builder<SourceDatasetStats> ().build ());
		}
		
		return new ListSourceDatasets (dataSourceId, categoryId); 
	}
	
	private static DomainQuery<List<Column>> listColumns (final String dataSourceId, final String sourceDatasetId) {
		if (dataSourceId == null || sourceDatasetId == null || dataSourceId.isEmpty () || sourceDatasetId.isEmpty ()) {
			return new Constant<List<Column>> (Collections.<Column>emptyList ());
		}
		
		return new ListColumns (dataSourceId, sourceDatasetId);
	}
	
	private static Promise<Result> renderCreateForm (final Form<DatasetForm> datasetForm) {
		final ActorSelection database = Akka.system().actorSelection (databaseRef);
		
		return from(database)
			.list(DataSource.class)
			.list(Category.class)
			.query (listSourceDatasets (datasetForm.field ("dataSourceId").value (), datasetForm.field ("categoryId").value ()))
			.query (listColumns (datasetForm.field ("dataSourceId").value (), datasetForm.field ("sourceDatasetId").value ()))
			.execute(new Function4<Page<DataSource>, Page<Category>, Page<SourceDatasetStats>, List<Column>, Result>() {
				@Override
				public Result apply(Page<DataSource> dataSources, Page<Category> categories, final Page<SourceDatasetStats> sourceDatasets, final List<Column> columns) throws Throwable {
					Logger.debug ("Columns: " + columns.size ());
					return ok (form.render (dataSources, categories, sourceDatasets, columns, datasetForm));
				}
			});
	}
	
	public static Promise<Result> createForm () {
		final Form<DatasetForm> datasetForm = Form.form (DatasetForm.class);
		
		return renderCreateForm (datasetForm);
	}
	
	public static Promise<Result> submitCreate () {
		final ActorSelection database = Akka.system().actorSelection (databaseRef);
		final Form<DatasetForm> datasetForm = Form.form (DatasetForm.class).bindFromRequest ();
		
		if (datasetForm.hasErrors ()) {
			return renderCreateForm (datasetForm);
		}
		
		final DatasetForm dataset = datasetForm.get ();
		
		return from (database)
				.get (DataSource.class, dataset.getDataSourceId ())
				.get (Category.class, dataset.getCategoryId ())
				.get (SourceDataset.class, dataset.getSourceDatasetId ())
				.executeFlat (new Function3<DataSource, Category, SourceDataset, Promise<Result>> () {
					@Override
					public Promise<Result> apply (final DataSource dataSource, final Category category, final SourceDataset sourceDataset) throws Throwable {
						Logger.debug ("dataSource: " + dataSource);
						Logger.debug ("category: " + category);
						Logger.debug ("sourceDataset: " + sourceDataset);

						return renderCreateForm (datasetForm);
					}
				});
	}
	
	public static Promise<Result> editForm (final String datasetId) {
		return Promise.pure ((Result) ok ());
	}
	
	public static Promise<Result> submitEdit (final String datasetId) {
		return Promise.pure ((Result) ok ());
	}
	
	public static Promise<Result> listByCategoryAndMessages (final String categoryId, final boolean listWithMessages, final long page) {
		// Hack: force the database actor to be loaded:
		if (Database.instance == null) {
			throw new NullPointerException ();
		}
		
		final ActorSelection database = Akka.system().actorSelection (databaseRef);
		
		return from (database)
			.list (Category.class)
			.get (Category.class, categoryId)
			.executeFlat (new Function2<Page<Category>, Category, Promise<Result>> () {
				@Override
				public Promise<Result> apply (final Page<Category> categories, final Category currentCategory) throws Throwable {
					
					return from (database)
							.query (new ListDatasets (currentCategory, page))
							.execute (new Function<Page<Dataset>, Result> () {
								@Override
								public Result apply (final Page<Dataset> datasets) throws Throwable {
									
//									return ok (list.render (listWithMessages));
									return ok (list.render (datasets, categories.values (), currentCategory, listWithMessages));
								}
								
							});
				}
			});
	}
	
	public static Promise<Result> listColumnsAction (final String dataSourceId, final String sourceDatasetId) {
		
		final ActorSelection database = Akka.system().actorSelection (databaseRef);
		
		return 
			from(database)
				.query(new ListColumns(dataSourceId, sourceDatasetId))
				.execute(new Function<List<Column>, Result>() {
	
					@Override
					public Result apply(List<Column> c) throws Throwable {
						return ok(columns.render(c, null));
					}
				});
	}

	public static class DatasetForm {
		
		@Constraints.Required
		@Constraints.MinLength (1)
		private String name;

		@Constraints.Required
		private String dataSourceId;
		
		@Constraints.Required
		private String categoryId;
		
		@Constraints.Required
		private String sourceDatasetId;

		@Constraints.Required
		private Map<String, String> columns;
		
		public String getName() {
			return name;
		}
		
		public void setName (final String name) {
			this.name = name;
		}
		
		public String getDataSourceId () {
			return dataSourceId;
		}

		public void setDataSourceId (final String dataSourceId) {
			this.dataSourceId = dataSourceId;
		}

		public String getCategoryId() {
			return categoryId;
		}
		
		public void setCategoryId (final String categoryId) {
			this.categoryId = categoryId;
		}
		
		public String getSourceDatasetId () {
			return sourceDatasetId;
		}
		
		public void setSourceDatasetId (final String sourceDatasetId) {
			this.sourceDatasetId = sourceDatasetId;
		}
		
		public Map<String, String> getColumns () {
			return columns;
		}
		
		public void setColumns (final Map<String, String> columns) {
			this.columns = columns;
		}
	}
}
