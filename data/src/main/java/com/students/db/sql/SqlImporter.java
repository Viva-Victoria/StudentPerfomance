package com.students.db.sql;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.students.db.model.Group;
import com.students.db.model.Import;
import com.students.db.model.Student;
import com.students.db.model.Teacher;
import com.students.db.repo.Database;
import com.students.util.Dates;
import com.students.util.Extractor;
import com.students.util.Hash;

public class SqlImporter extends SqlRepository {
	private static final String last = Extractor.readText("sql/import/last.sql"),
								insert = Extractor.readText("sql/import/insert.sql");
	private String salt;
	private int saltPosition;

	public SqlImporter(String salt, int saltPosition, Database database) {
		super(database);
		
		this.salt = salt;
		this.saltPosition = saltPosition;
		
		Mapping.getInstance().register(Import.class, r -> {
			var i = new Import();
			
			i.setId(UUID.fromString(r.getString("import_id")));
			i.setTable(r.getString("import_table"));
			i.setVersion(r.getInt("import_version"));
			i.setDate(r.getTimestamp("import_date"));
			
			return i;
		});
	}
	
	private <T> List<T> makeImport(TypeReference<List<T>> type, String table) throws IOException, SQLException {
		Import lastImport = database.query(Import.class, last, table);
		
		int version = 0;
		if (lastImport != null) {
			version = lastImport.getVersion();
		}
		
		version++;
		try(InputStream s = Extractor.openStream("sql/import/v%d/%s.json".formatted(version, table))) {
			if(s == null) {
				return null;
			}
			
			var mapper = new ObjectMapper();
			return mapper.readValue(s, type);
		}
	}
	
	private void saveImport(String table) throws SQLException {
		System.out.println(table);
		Import lastImport = database.query(Import.class, last, table);
		System.out.println(lastImport);
		
		int version = 0;
		if (lastImport != null) {
			version = lastImport.getVersion();
		}
		System.out.println(version);
		
		database.execute(insert, UUID.randomUUID(), table, version + 1, Dates.sqlDate(Dates.now()));
		System.out.println(version);
	}
	
	public void importTeachers() throws SQLException, IOException {
		List<Teacher> data = makeImport(new TypeReference<List<Teacher>>() {}, "teacher");
		if (data == null) {
			return;
		}
		
		var repo = new TeacherRepository(database);
		for (var t : data) {
			var auth = t.getAuth();
			
			auth.setPasswordHash(Hash.hash(auth.getPassword(), salt, saltPosition));
			repo.insert(t);
		}
		
		saveImport("teacher");
	}
	
	public void importStudents() throws SQLException, IOException {
		List<Student> data = makeImport(new TypeReference<List<Student>>() {}, "student");
		if (data == null) {
			return;
		}
		
		var repo = new StudentRepository(database);
		for (var s : data) {
			repo.insert(s);
		}
		
		saveImport("student");
	}

	public static class LinkedGroup extends Group {
		private List<UUID> students;

		public List<UUID> getStudents() {
			return students;
		}

		public void setStudents(List<UUID> students) {
			this.students = students;
		}
	}
	
	public void importGroups() throws SQLException, IOException {
		List<LinkedGroup> data = makeImport(new TypeReference<List<LinkedGroup>>() {}, "groups");
		if (data == null) {
			return;
		}
		
		var repo = new GroupRepository(database);
		for (var d : data) {
			repo.insert(d);
			
			for (var id : d.getStudents()) {
				repo.moveStudent(UUID.randomUUID(), id, d.getId());
			}
		}
		
		saveImport("groups");
	}
}
