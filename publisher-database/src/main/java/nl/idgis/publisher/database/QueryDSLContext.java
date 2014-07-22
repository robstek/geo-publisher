package nl.idgis.publisher.database;

import com.mysema.query.sql.RelationalPath;
import com.mysema.query.sql.SQLQuery;
import com.mysema.query.sql.SQLTemplates;
import com.mysema.query.sql.dml.SQLInsertClause;

class QueryDSLContext {

	private JdbcContext jdbcContext;
	private final SQLTemplates templates;

	
	QueryDSLContext(JdbcContext jdbcContext, SQLTemplates templates) {
		this.jdbcContext = jdbcContext;
		this.templates = templates;		
	}
	
	JdbcContext jdbc() {
		return jdbcContext;
	}

	SQLQuery query() {
		return new SQLQuery(jdbcContext.getConnection(), templates);
	}
	
	SQLInsertClause insert(RelationalPath<?> entity) {
		return new SQLInsertClause(jdbcContext.getConnection(), templates, entity);
	}
	
	void answer(Object msg) {
		jdbcContext.answer(msg);
	}
}
