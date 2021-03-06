package nl.idgis.publisher.job;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import nl.idgis.publisher.AbstractServiceTest;

import nl.idgis.publisher.database.messages.CreateDataset;
import nl.idgis.publisher.database.messages.DataSourceStatus;
import nl.idgis.publisher.database.messages.DatasetStatusInfo;
import nl.idgis.publisher.database.messages.GetDataSourceStatus;
import nl.idgis.publisher.database.messages.GetDatasetStatus;
import nl.idgis.publisher.database.messages.RegisterSourceDataset;
import nl.idgis.publisher.database.messages.Registered;

import nl.idgis.publisher.domain.job.JobState;
import nl.idgis.publisher.domain.service.Column;
import nl.idgis.publisher.domain.service.Dataset;
import nl.idgis.publisher.domain.service.Table;

import nl.idgis.publisher.job.messages.CreateHarvestJob;
import nl.idgis.publisher.job.messages.CreateImportJob;
import nl.idgis.publisher.job.messages.GetHarvestJobs;
import nl.idgis.publisher.job.messages.GetImportJobs;
import nl.idgis.publisher.protocol.messages.Ack;
import nl.idgis.publisher.utils.TypedList;

import org.junit.Test;

import static nl.idgis.publisher.database.QDataSource.dataSource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StatusAndJobTest extends AbstractServiceTest {

	@Test
	public void testDataSource() throws Exception {
		insert(dataSource)
			.set(dataSource.identification, "testDataSource")
			.set(dataSource.name, "My Test DataSource")
			.execute();
		
		Object result = sync.ask(database, new GetDataSourceStatus());
		assertEquals(TypedList.class, result.getClass());
		
		TypedList<?> typedList = (TypedList<?>)result;
		assertTrue(typedList.contains(DataSourceStatus.class));
		
		Iterator<DataSourceStatus> itr = typedList.cast(DataSourceStatus.class).iterator();
		assertNotNull(itr);
		assertTrue(itr.hasNext());
		
		DataSourceStatus status = itr.next();
		assertEquals("testDataSource", status.getDataSourceId());
		assertNull(status.getLastHarvested());
		assertNull(status.getFinishedState());
		
		assertNotNull(status);
		
		assertFalse(itr.hasNext());
		
		result = sync.ask(jobManager, new CreateHarvestJob("testDataSource"));
		assertEquals(Ack.class, result.getClass());
		
		executeJobs(new GetHarvestJobs());
		
		result = sync.ask(database, new GetDataSourceStatus());
		assertEquals(TypedList.class, result.getClass());
		
		typedList = (TypedList<?>)result;
		assertTrue(typedList.contains(DataSourceStatus.class));
		
		itr = typedList.cast(DataSourceStatus.class).iterator();
		assertNotNull(itr);
		assertTrue(itr.hasNext());
		
		status = itr.next();
		assertEquals("testDataSource", status.getDataSourceId());
		assertEquals(JobState.SUCCEEDED, status.getFinishedState());
		assertNotNull(status.getLastHarvested());
	}
	
	@Test
	public void testDataset() throws Exception {
		insert(dataSource)
			.set(dataSource.identification, "testDataSource")
			.set(dataSource.name, "My Test DataSource")
			.execute();
		
		Dataset dataset = createTestDataset();
		Object result = sync.ask(database, new RegisterSourceDataset("testDataSource", dataset));
		assertEquals(Registered.class, result.getClass());
		
		Table table = dataset.getTable();
		List<Column> columns = Arrays.asList(table.getColumns().get(0));
		result = sync.ask(database, new CreateDataset(
				"testDataset", 
				"My Test Dataset", 
				dataset.getId(),
				columns,
				"{ \"expression\": null }"));
		
		result = sync.ask(database, new GetDatasetStatus());
		assertEquals(TypedList.class, result.getClass());
		
		TypedList<?> typedList = (TypedList<?>)result;
		assertTrue(typedList.contains(DatasetStatusInfo.class));
		
		Iterator<DatasetStatusInfo> itr = typedList.cast(DatasetStatusInfo.class).iterator();
		assertNotNull(itr);
		
		assertTrue(itr.hasNext());
		
		DatasetStatusInfo status = itr.next();
		assertEquals("testDataset", status.getDatasetId());		
		assertNotNull(status);
		
		assertFalse(itr.hasNext());
		
		result = sync.ask(database, new GetDatasetStatus("testDataset"));
		assertEquals(DatasetStatusInfo.class, result.getClass());
		
		status = (DatasetStatusInfo)result;
		assertEquals("testDataset", status.getDatasetId());		
		assertNotNull(status);
		
		for(int i = 0; i < 10; i++) {
			result = sync.ask(jobManager, new CreateImportJob("testDataset"));
			assertEquals(Ack.class, result.getClass());
		
			executeJobs(new GetImportJobs());
		}
		
		result = sync.ask(database, new GetDatasetStatus());
		assertEquals(TypedList.class, result.getClass());
		
		typedList = (TypedList<?>)result;
		assertTrue(typedList.contains(DatasetStatusInfo.class));
		
		itr = typedList.cast(DatasetStatusInfo.class).iterator();
		assertNotNull(itr);
		
		assertTrue(itr.hasNext());
		
		status = itr.next();
		assertEquals("testDataset", status.getDatasetId());		
		assertNotNull(status);
		
		assertFalse(itr.hasNext());
	}
}
