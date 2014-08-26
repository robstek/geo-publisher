package nl.idgis.publisher.admin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nl.idgis.publisher.database.messages.CategoryInfo;
import nl.idgis.publisher.database.messages.CreateDataset;
import nl.idgis.publisher.database.messages.CreateImportJob;
import nl.idgis.publisher.database.messages.DataSourceInfo;
import nl.idgis.publisher.database.messages.DatasetInfo;
import nl.idgis.publisher.database.messages.DeleteDataset;
import nl.idgis.publisher.database.messages.GetCategoryInfo;
import nl.idgis.publisher.database.messages.GetCategoryListInfo;
import nl.idgis.publisher.database.messages.GetDataSourceInfo;
import nl.idgis.publisher.database.messages.GetDatasetColumns;
import nl.idgis.publisher.database.messages.GetDatasetInfo;
import nl.idgis.publisher.database.messages.GetDatasetListInfo;
import nl.idgis.publisher.database.messages.GetJobLog;
import nl.idgis.publisher.database.messages.GetSourceDatasetColumns;
import nl.idgis.publisher.database.messages.GetSourceDatasetInfo;
import nl.idgis.publisher.database.messages.GetSourceDatasetListInfo;
import nl.idgis.publisher.database.messages.HarvestJobInfo;
import nl.idgis.publisher.database.messages.ImportJobInfo;
import nl.idgis.publisher.database.messages.InfoList;
import nl.idgis.publisher.database.messages.JobInfo;
import nl.idgis.publisher.database.messages.SourceDatasetInfo;
import nl.idgis.publisher.database.messages.StoredJobLog;
import nl.idgis.publisher.database.messages.UpdateDataset;
import nl.idgis.publisher.domain.MessageType;
import nl.idgis.publisher.domain.job.JobType;
import nl.idgis.publisher.domain.job.LogLevel;
import nl.idgis.publisher.domain.query.DeleteEntity;
import nl.idgis.publisher.domain.query.GetEntity;
import nl.idgis.publisher.domain.query.ListDatasetColumns;
import nl.idgis.publisher.domain.query.ListDatasets;
import nl.idgis.publisher.domain.query.ListEntity;
import nl.idgis.publisher.domain.query.ListIssues;
import nl.idgis.publisher.domain.query.ListSourceDatasetColumns;
import nl.idgis.publisher.domain.query.ListSourceDatasets;
import nl.idgis.publisher.domain.query.PutEntity;
import nl.idgis.publisher.domain.query.RefreshDataset;
import nl.idgis.publisher.domain.response.Page;
import nl.idgis.publisher.domain.response.Page.Builder;
import nl.idgis.publisher.domain.response.Response;
import nl.idgis.publisher.domain.service.CrudOperation;
import nl.idgis.publisher.domain.web.ActiveTask;
import nl.idgis.publisher.domain.web.Category;
import nl.idgis.publisher.domain.web.DataSource;
import nl.idgis.publisher.domain.web.DataSourceStatusType;
import nl.idgis.publisher.domain.web.Dataset;
import nl.idgis.publisher.domain.web.EntityRef;
import nl.idgis.publisher.domain.web.EntityType;
import nl.idgis.publisher.domain.web.Filter;
import nl.idgis.publisher.domain.web.Issue;
import nl.idgis.publisher.domain.web.Message;
import nl.idgis.publisher.domain.web.NotFound;
import nl.idgis.publisher.domain.web.Notification;
import nl.idgis.publisher.domain.web.PutDataset;
import nl.idgis.publisher.domain.web.SourceDataset;
import nl.idgis.publisher.domain.web.SourceDatasetStats;
import nl.idgis.publisher.domain.web.Status;
import nl.idgis.publisher.harvester.messages.GetActiveDataSources;
import nl.idgis.publisher.messages.ActiveJob;
import nl.idgis.publisher.messages.ActiveJobs;
import nl.idgis.publisher.messages.GetActiveJobs;
import nl.idgis.publisher.messages.Progress;

import org.joda.time.LocalDateTime;

import scala.concurrent.Future;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.dispatch.Futures;
import akka.dispatch.Mapper;
import akka.dispatch.OnComplete;
import akka.dispatch.OnSuccess;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.Patterns;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Iterables;
import com.mysema.query.types.Order;

public class Admin extends UntypedActor {
	
	private final long ITEMS_PER_PAGE = 20;
	
	private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	
	private final ActorRef database, harvester, loader;
	
	private final ObjectMapper objectMapper = new ObjectMapper ();
	
	public Admin(ActorRef database, ActorRef harvester, ActorRef loader) {
		this.database = database;
		this.harvester = harvester;
		this.loader = loader;
	}
	
	public static Props props(ActorRef database, ActorRef harvester, ActorRef loader) {
		return Props.create(Admin.class, database, harvester, loader);
	}

	@Override
	public void onReceive (final Object message) throws Exception {
		if (message instanceof ListEntity<?>) {
			final ListEntity<?> listEntity = (ListEntity<?>)message;
			
			if (listEntity.cls ().equals (DataSource.class)) {
				handleListDataSources (listEntity);
			} else if (listEntity.cls ().equals (Category.class)) {
				handleListCategories (listEntity);
			} else if (listEntity.cls ().equals (Dataset.class)) {
				handleListDatasets (null);
			} else if (listEntity.cls ().equals (Notification.class)) {
				handleListDashboardNotifications (null);
			} else if (listEntity.cls ().equals (ActiveTask.class)) {
				handleListDashboardActiveTasks (null);
			} else if (listEntity.cls ().equals (Issue.class)) {
				handleListDashboardIssues (null);
			} else {
				handleEmptyList (listEntity);
			}
		} else if (message instanceof GetEntity<?>) {
			final GetEntity<?> getEntity = (GetEntity<?>)message;
			
			if (getEntity.cls ().equals (DataSource.class)) {
				handleGetDataSource (getEntity);
			} else if (getEntity.cls ().equals (Category.class)) {
				handleGetCategory (getEntity);
			} else if (getEntity.cls ().equals (Dataset.class)) {
				handleGetDataset(getEntity);
			} else if (getEntity.cls ().equals (SourceDataset.class)) {
				handleGetSourceDataset(getEntity);
			} else {
				sender ().tell (null, self ());
			}
		} else if (message instanceof PutEntity<?>) {
			final PutEntity<?> putEntity = (PutEntity<?>)message;
			if (putEntity.value() instanceof PutDataset) {
				PutDataset putDataset = (PutDataset)putEntity.value(); 
				if (putDataset.getOperation() == CrudOperation.CREATE){
					handleCreateDataset(putDataset);
				}else{
					handleUpdateDataset(putDataset);
				}
			}
			
		} else if (message instanceof DeleteEntity<?>) {
			final DeleteEntity<?> delEntity = (DeleteEntity<?>)message;
			if (delEntity.cls ().equals (Dataset.class)) {
				handleDeleteDataset(delEntity.id());
			}
		} else if (message instanceof ListSourceDatasets) {
			handleListSourceDatasets ((ListSourceDatasets)message);
		} else if (message instanceof ListDatasets) {
			handleListDatasets (((ListDatasets)message));
		} else if (message instanceof ListSourceDatasetColumns) {
			handleListSourceDatasetColumns ((ListSourceDatasetColumns) message);
		} else if (message instanceof ListDatasetColumns) {
			handleListDatasetColumns ((ListDatasetColumns) message);
		} else if (message instanceof RefreshDataset) {
			handleRefreshDataset(((RefreshDataset) message).getDatasetId());
		} else if (message instanceof ListIssues) {
			handleListIssues ((ListIssues)message);
		} else {
			unhandled (message);
		}
	}
	
	private void handleCreateDataset(PutDataset putDataset) throws JsonProcessingException {
		log.debug ("handle create dataset: " + putDataset.id());
		
		final ActorRef sender = getSender(), self = getSelf();
		
		final Future<Object> createDatasetInfo = Patterns.ask(database, 
				new CreateDataset(putDataset.id(), putDataset.getDatasetName(),
				putDataset.getSourceDatasetIdentification(), putDataset.getColumnList(),
				objectMapper.writeValueAsString (putDataset.getFilterConditions())), 15000);
				createDatasetInfo.onSuccess(new OnSuccess<Object>() {
					@Override
					public void onSuccess(Object msg) throws Throwable {
						Response <?> createdDataset = (Response<?>)msg;
						log.debug ("created dataset id: " + createdDataset.getValue());
						sender.tell (createdDataset, self);
					}
				}, getContext().dispatcher());

	}

	private void handleUpdateDataset(PutDataset putDataset) throws JsonProcessingException {
		log.debug ("handle update dataset: " + putDataset.id());
		
		final ActorRef sender = getSender(), self = getSelf();
		
		final Future<Object> updateDatasetInfo = Patterns.ask(database, 
				new UpdateDataset(putDataset.id(), putDataset.getDatasetName(),
				putDataset.getSourceDatasetIdentification(), putDataset.getColumnList(),
				objectMapper.writeValueAsString (putDataset.getFilterConditions ())), 15000);
				updateDatasetInfo.onSuccess(new OnSuccess<Object>() {
					@Override
					public void onSuccess(Object msg) throws Throwable {
						Response<?> updatedDataset = (Response<?>)msg;
						log.debug ("updated dataset id: " + updatedDataset.getValue());
						sender.tell (updatedDataset, self);
					}
				}, getContext().dispatcher());

	}

	private void handleDeleteDataset(String id) {
		log.debug ("handle delete dataset: " + id);
		
		final ActorRef sender = getSender(), self = getSelf();
		
		final Future<Object> deleteDatasetInfo = Patterns.ask(database, new DeleteDataset(id), 15000);
				deleteDatasetInfo.onSuccess(new OnSuccess<Object>() {
					@Override
					public void onSuccess(Object msg) throws Throwable {
						Response<?> deletedDataset = (Response<?>)msg;
						log.debug ("deleted dataset id: " + deletedDataset.getValue());
						sender.tell (deletedDataset, self);
					}
				}, getContext().dispatcher());

	}
	
	private void handleRefreshDataset(String datasetId) {
		log.debug("requesting to refresh dataset: " + datasetId);
		
		final ActorRef sender = getSender(), self = getSelf();
		Patterns.ask(database, new CreateImportJob(datasetId), 15000)
			.onComplete(new OnComplete<Object>() {

				@Override
				public void onComplete(Throwable t, Object msg) throws Throwable {
					sender.tell(t == null, self);
				}
				
			}, getContext().dispatcher());
	}
	
	private void handleListSourceDatasetColumns (final ListSourceDatasetColumns listColumns) {
		GetSourceDatasetColumns di = new GetSourceDatasetColumns(listColumns.getDataSourceId(), listColumns.getSourceDatasetId());
		
		database.tell(di, getSender());
	}

	private void handleListDatasetColumns (final ListDatasetColumns listColumns) {
		GetDatasetColumns di = new GetDatasetColumns(listColumns.getDatasetId());
		
		database.tell(di, getSender());
	}

	private void handleListDataSources (final ListEntity<?> listEntity) {
		log.debug ("List received for: " + listEntity.cls ().getCanonicalName ());
		
		final ActorRef sender = getSender(), self = getSelf();
		
		final Future<Object> activeDataSources = Patterns.ask(harvester, new GetActiveDataSources(), 15000);
		final Future<Object> dataSourceInfo = Patterns.ask(database, new GetDataSourceInfo(), 15000);
		
		activeDataSources.onSuccess(new OnSuccess<Object>() {
			
			@Override
			@SuppressWarnings("unchecked")
			public void onSuccess(Object msg) throws Throwable {
				final Set<String> activeDataSources = (Set<String>)msg;
				log.debug("active data sources received");
				
				dataSourceInfo.onSuccess(new OnSuccess<Object>() {

					@Override
					public void onSuccess(Object msg) throws Throwable {
						List<DataSourceInfo> dataSourceInfoList = (List<DataSourceInfo>)msg;
						log.debug("data sources info received");
						
						final Page.Builder<DataSource> pageBuilder = new Page.Builder<> ();
						
						for(DataSourceInfo dataSourceInfo : dataSourceInfoList) {
							final String id = dataSourceInfo.getId();
							final DataSource dataSource = new DataSource (
									id, 
									dataSourceInfo.getName(),
									new Status (activeDataSources.contains(id) 
											? DataSourceStatusType.OK
											: DataSourceStatusType.NOT_CONNECTED, LocalDateTime.now ()));
							
							pageBuilder.add (dataSource);
						}
						
						log.debug("sending data source page");
						sender.tell (pageBuilder.build (), self);
					}
				}, getContext().dispatcher());
			}			
		}, getContext().dispatcher());
	}
	
	private void handleListCategories (final ListEntity<?> listEntity) {
		log.debug ("handleCategoryList");
		
		final ActorRef sender = getSender(), self = getSelf();
		
		final Future<Object> categoryListInfo = Patterns.ask(database, new GetCategoryListInfo(), 15000);
				categoryListInfo.onSuccess(new OnSuccess<Object>() {
					@SuppressWarnings("unchecked")
					@Override
					public void onSuccess(Object msg) throws Throwable {
						List<CategoryInfo> categoryListInfoList = (List<CategoryInfo>)msg;
						log.debug("data sources info received");
						
						final Page.Builder<Category> pageBuilder = new Page.Builder<> ();
						
						for(CategoryInfo categoryInfo : categoryListInfoList) {
							pageBuilder.add (new Category (categoryInfo.getId(), categoryInfo.getName()));
						}
						
						log.debug("sending category list");
						sender.tell (pageBuilder.build (), self);
					}
				}, getContext().dispatcher());
	}
	
	private void handleListDashboardIssues(Object object) {
		log.debug ("handleListDashboardIssues");
		
		handleListIssues (new ListIssues (LogLevel.WARNING.andUp ()));
	}

	private void handleListDashboardActiveTasks(Object object) {
		log.debug ("handleDashboardActiveTaskList");
		
		final Future<Object> dataSourceInfo = Patterns.ask(database, new GetDataSourceInfo(), 15000);
		final Future<Object> harvestJobs = Patterns.ask(harvester, new GetActiveJobs(), 15000);
		final Future<Object> loaderJobs = Patterns.ask(loader, new GetActiveJobs(), 15000);
		
		final Future<Map<String, String>> dataSourceNames = dataSourceInfo.map(new Mapper<Object, Map<String, String>>() {
			
			@SuppressWarnings("unchecked")
			public Map<String, String> apply(Object msg) {
				List<DataSourceInfo> dataSourceInfos = (List<DataSourceInfo>)msg;
				
				Map<String, String> retval = new HashMap<String, String>();
				for(DataSourceInfo dataSourceInfo : dataSourceInfos) {
					retval.put(dataSourceInfo.getId(), dataSourceInfo.getName());
				}
				
				return retval;
			}
			
		}, getContext().dispatcher());
		
		final Future<Iterable<ActiveTask>> activeHarvestTasks = 
			harvestJobs.flatMap(new Mapper<Object, Future<Iterable<ActiveTask>>>() {

			@Override
			public Future<Iterable<ActiveTask>> apply(Object msg) {
				final ActiveJobs activeJobs = (ActiveJobs)msg;
				
				return dataSourceNames.map(new Mapper<Map<String, String>, Iterable<ActiveTask>>() {
					
					public Iterable<ActiveTask> apply(Map<String, String> dataSourceNames) {
						List<ActiveTask> activeTasks = new ArrayList<>();
						
						for(ActiveJob activeJob : activeJobs.getActiveJobs()) {
							HarvestJobInfo harvestJob = (HarvestJobInfo)activeJob.getJob();
							 
							activeTasks.add(
									new ActiveTask(
											"" + harvestJob.getId(), 
											dataSourceNames.get(harvestJob.getDataSourceId()), 
											new Message(JobType.HARVEST, null), 
											null));
						}
						
						return activeTasks;
					}
				}, getContext().dispatcher());
			}
			
		}, getContext().dispatcher());
		
		final Future<Iterable<ActiveTask>> activeLoaderTasks = 
			loaderJobs.flatMap(new Mapper<Object, Future<Iterable<ActiveTask>>>() {
				
				public Future<Iterable<ActiveTask>> apply(Object msg) {
					ActiveJobs activeJobs = (ActiveJobs)msg;
					
					List<Future<ActiveTask>> activeTasks = new ArrayList<>(); 
					Map<String, Future<Object>> datasetInfos = new HashMap<String, Future<Object>>();
					for(ActiveJob activeJob : activeJobs.getActiveJobs()) {
						final ImportJobInfo job = (ImportJobInfo)activeJob.getJob();
						final Progress progress = (Progress)activeJob.getProgress();
						
						String datasetId = job.getDatasetId();
						if(!datasetInfos.containsKey(datasetId)) {
							datasetInfos.put(
									datasetId,							
									Patterns.ask(
											database, 
											new GetDatasetInfo(datasetId), 
											15000));
						}
						
						activeTasks.add(datasetInfos.get(datasetId).map(new Mapper<Object, ActiveTask>() {
							
							public ActiveTask apply(Object msg) {
								DatasetInfo datasetInfo = (DatasetInfo)msg;
								
								return new ActiveTask(
									"" + job.getId(),
									datasetInfo.getName(),
									new Message(JobType.IMPORT, null),
									(int)(progress.getCount() * 100 / progress.getTotalCount()));
							}
							
						}, getContext().dispatcher()));
					}
					
					return Futures.sequence(activeTasks, getContext().dispatcher());
				}
				
			}, getContext().dispatcher());
		
		final ActorRef sender = getSender();
		activeHarvestTasks.flatMap(new Mapper<Iterable<ActiveTask>, Future<Iterable<ActiveTask>>>() {
			
			public Future<Iterable<ActiveTask>> apply(final Iterable<ActiveTask> activeHarvestTasks) {
				return activeLoaderTasks.map(new Mapper<Iterable<ActiveTask>, Iterable<ActiveTask>>() {
					
					public Iterable<ActiveTask> apply(final Iterable<ActiveTask> activeLoaderTasks) {
						return Iterables.concat(activeHarvestTasks, activeLoaderTasks);
					}
				}, getContext().dispatcher());
			}
			
		}, getContext().dispatcher()).onSuccess(new OnSuccess<Iterable<ActiveTask>>() {

			@Override
			public void onSuccess(Iterable<ActiveTask> activeTasks) throws Throwable {
				Builder<ActiveTask> builder = new Page.Builder<>();
				for(ActiveTask activeTask : activeTasks) {
					builder.add(activeTask);
				}
				sender.tell(builder.build(), getSelf());				
			}
			
		}, getContext().dispatcher());
	}

	private void handleListDashboardNotifications(Object object) {
		log.debug ("handleDashboardNotificationList");
		
		final ActorRef sender = getSender();
		 
		final Page.Builder<Notification> dashboardNotifications = new Page.Builder<Notification> ();
		
		sender.tell (dashboardNotifications.build (), getSelf());
	}

	private void handleEmptyList (final ListEntity<?> listEntity) {
		final Page.Builder<Category> builder = new Page.Builder<> ();
		
		sender ().tell (builder.build (), self ());
	}
	
	private void handleGetDataSource (final GetEntity<?> getEntity) {
		final DataSource dataSource = new DataSource (getEntity.id (), "DataSource: " + getEntity.id (), new Status (DataSourceStatusType.OK, LocalDateTime.now ()));
		
		sender ().tell (dataSource, self ());
	}
	
	private void handleGetCategory (final GetEntity<?> getEntity) {
		log.debug ("handleCategory");
		
		final ActorRef sender = getSender();
		
		final Future<Object> categoryInfo = Patterns.ask(database, new GetCategoryInfo(getEntity.id ()), 15000);
				categoryInfo.onSuccess(new OnSuccess<Object>() {
					@Override
					public void onSuccess(Object msg) throws Throwable {
						if(msg instanceof CategoryInfo) {
							CategoryInfo categoryInfo = (CategoryInfo)msg;
							log.debug("category info received");
							Category category = new Category (categoryInfo.getId(), categoryInfo.getName());
							log.debug("sending category: " + category);
							sender.tell (category, getSelf());
						} else {
							sender.tell (new NotFound(), getSelf());
						}
					}
				}, getContext().dispatcher());
	}
	
	private void handleGetDataset (final GetEntity<?> getEntity) {
		log.debug ("handleDataset");
		
		final ActorRef sender = getSender();
		
		final Future<Object> datasetInfo = Patterns.ask(database, new GetDatasetInfo(getEntity.id ()), 15000);
				datasetInfo.onSuccess(new OnSuccess<Object>() {
					@Override
					public void onSuccess(Object msg) throws Throwable {
						if(msg instanceof DatasetInfo) {
							DatasetInfo datasetInfo = (DatasetInfo)msg;
							log.debug("dataset info received");
							Dataset dataset = 
									new Dataset (datasetInfo.getId().toString(), datasetInfo.getName(),
											new Category(datasetInfo.getCategoryId(), datasetInfo.getCategoryName()),
											new Status (DataSourceStatusType.OK, LocalDateTime.now ()),
											null, // notification list
											new EntityRef (EntityType.SOURCE_DATASET, datasetInfo.getSourceDatasetId(), datasetInfo.getSourceDatasetName()),
											new ObjectMapper().readValue (datasetInfo.getFilterConditions (), Filter.class)
									);
							log.debug("sending dataset: " + dataset);
							sender.tell (dataset, getSelf());
						} else {
							sender.tell (new NotFound(), getSelf());
						}
					}
				}, getContext().dispatcher());
	}
	
	private void handleGetSourceDataset (final GetEntity<?> getEntity) {
		log.debug ("handleSourceDataset");
		
		final ActorRef sender = getSender();
		
		final Future<Object> sourceDatasetInfo = Patterns.ask(database, new GetSourceDatasetInfo(getEntity.id ()), 15000);
				sourceDatasetInfo.onSuccess(new OnSuccess<Object>() {
					@Override
					public void onSuccess(Object msg) throws Throwable {
						if(msg instanceof SourceDatasetInfo) {
							SourceDatasetInfo sourceDatasetInfo = (SourceDatasetInfo)msg;
							log.debug("sourcedataset info received");
							final SourceDataset sourceDataset = new SourceDataset (
									sourceDatasetInfo.getId(), 
									sourceDatasetInfo.getName(),
									new EntityRef (EntityType.CATEGORY, sourceDatasetInfo.getCategoryId(),sourceDatasetInfo.getCategoryName()),
									new EntityRef (EntityType.DATA_SOURCE, sourceDatasetInfo.getDataSourceId(), sourceDatasetInfo.getDataSourceName())
							);
							log.debug("sending source_dataset: " + sourceDataset);
							sender.tell (sourceDataset, getSelf());
						} else {
							sender.tell (new NotFound(), getSelf());
						}
					}
				}, getContext().dispatcher());
	}
	
	private void handleListSourceDatasets (final ListSourceDatasets message) {
		
		log.debug ("handleListSourceDatasets");
		
		final ActorRef sender = getSender(), self = getSelf();
		
		final Long page = message.getPage();
		
		final Long offset, limit;		 
		if(page == null) {
			offset = null;
			limit = null;
		} else {
			offset = (page - 1) * ITEMS_PER_PAGE;
			limit = ITEMS_PER_PAGE;
		}
		
		final Future<Object> sourceDatasetInfo = Patterns.ask(database, new GetSourceDatasetListInfo(message.dataSourceId(), message.categoryId(), message.getSearchString(), offset, limit), 15000);
		
				sourceDatasetInfo.onSuccess(new OnSuccess<Object>() {

					@SuppressWarnings("unchecked")
					@Override
					public void onSuccess(Object msg) throws Throwable {
						InfoList<SourceDatasetInfo> sourceDatasetInfoList = (InfoList<SourceDatasetInfo>)msg;
						log.debug("data sources info received");
						
						final Page.Builder<SourceDatasetStats> pageBuilder = new Page.Builder<> ();
						
						for(SourceDatasetInfo sourceDatasetInfo : sourceDatasetInfoList.getList()) {
							final SourceDataset sourceDataset = new SourceDataset (
									sourceDatasetInfo.getId(), 
									sourceDatasetInfo.getName(),
									new EntityRef (EntityType.CATEGORY, sourceDatasetInfo.getCategoryId(),sourceDatasetInfo.getCategoryName()),
									new EntityRef (EntityType.DATA_SOURCE, sourceDatasetInfo.getDataSourceId(), sourceDatasetInfo.getDataSourceName())
							);
							
							pageBuilder.add (new SourceDatasetStats (sourceDataset, sourceDatasetInfo.getCount()));
						}
						
						if(page != null) {
							long count = sourceDatasetInfoList.getCount();
							long pages = count / ITEMS_PER_PAGE + Math.min(1, count % ITEMS_PER_PAGE);
							
							if(pages > 1) {
								pageBuilder
									.setHasMorePages(true)
									.setPageCount(pages)
									.setCurrentPage(page);
							}
						}
						
						
						log.debug("sending data source page");
						sender.tell (pageBuilder.build (), self);
					}
				}, getContext().dispatcher());
		
	}

	private void handleListDatasets (final ListDatasets listDatasets) {
		String categoryId = listDatasets.categoryId();
		log.debug ("handleListDatasets categoryId=" + categoryId);
		
		final ActorRef sender = getSender(), self = getSelf();
		
		final Future<Object> datasetInfo = Patterns.ask(database, new GetDatasetListInfo(categoryId), 15000);
		
				datasetInfo.onSuccess(new OnSuccess<Object>() {

					@SuppressWarnings("unchecked")
					@Override
					public void onSuccess(Object msg) throws Throwable {
						List<DatasetInfo> datasetInfoList = (List<DatasetInfo>)msg;
						log.debug("data sources info received");
						
						final Page.Builder<Dataset> pageBuilder = new Page.Builder<> ();
						final ObjectMapper objectMapper = new ObjectMapper ();
						
						for(DatasetInfo datasetInfo : datasetInfoList) {
							final Dataset dataset =  new Dataset (datasetInfo.getId().toString(), datasetInfo.getName(),
									new Category(datasetInfo.getCategoryId(), datasetInfo.getCategoryName()),
									new Status (DataSourceStatusType.OK, LocalDateTime.now ()),
									null, // notification list
									new EntityRef (EntityType.SOURCE_DATASET, datasetInfo.getSourceDatasetId(), datasetInfo.getSourceDatasetName()),
									objectMapper.readValue (datasetInfo.getFilterConditions (), Filter.class)
							);
							
							pageBuilder.add (dataset);
						}
						
						log.debug("sending dataset page");
						sender.tell (pageBuilder.build (), self);
					}
				}, getContext().dispatcher());
		
	}

	private void handleListIssues (final ListIssues listIssues) {
		log.debug("handleListIssues logLevels=" + listIssues.getLogLevels () + ", since=" + listIssues.getSince () + ", page=" + listIssues.getPage () + ", limit=" + listIssues.getLimit ());
		
		final ActorRef sender = sender ();
		final ActorRef self = self ();

		final long page = listIssues.getPage () != null ? Math.max (1, listIssues.getPage ()) : 1;
		final long limit = listIssues.getLimit () != null ? Math.max (1, listIssues.getLimit ()) : ITEMS_PER_PAGE;
		final long offset = Math.max (0, (page - 1) * limit);
		
		final Future<Object> issues = Patterns.ask (database, new GetJobLog (Order.DESC, offset, limit, listIssues.getLogLevels (), listIssues.getSince ()), 15000);
		
		issues.onSuccess (new OnSuccess<Object> () {
			@Override
			public void onSuccess (final Object msg) throws Throwable {
				final Page.Builder<Issue> dashboardIssues = new Page.Builder<Issue>();
				
				@SuppressWarnings("unchecked")
				InfoList<StoredJobLog> jobLogs = (InfoList<StoredJobLog>)msg;
				for(StoredJobLog jobLog : jobLogs.getList ()) {
					JobInfo job = jobLog.getJob();
					
					MessageType type = jobLog.getType();
					
					dashboardIssues.add(
						new Issue(
								"" + job.getId(),
								new Message(
										type,
										Arrays.asList(jobLog.getContent())),
								jobLog.getLevel(),
								job.getJobType()));
					
				}

				// Paging:
				long count = jobLogs.getCount();
				long pages = count / limit + Math.min(1, count % limit);
				
				if(pages > 1) {
					dashboardIssues
						.setHasMorePages(true)
						.setPageCount(pages)
						.setCurrentPage(page);
				}
				
				sender.tell(dashboardIssues.build(), self);
			}
		}, getContext().dispatcher());
	}
}
