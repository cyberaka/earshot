package com.earshot.api.speaker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.earshot.api.config.EarshotProperties;
import com.earshot.api.export.MarkdownExporter;
import com.earshot.api.job.JobWorker;
import com.earshot.api.job.Jobs;
import com.earshot.api.ml.MlClient;

/** Person library, voiceprints, naming, merge and delete (PRD §7). */
@Service
public class PeopleService {

	private static final Logger log = LoggerFactory.getLogger(PeopleService.class);
	private final JdbcClient db;
	private final MlClient ml;
	private final Jobs jobs;
	private final JobWorker worker;
	private final MarkdownExporter exporter;
	private final Path clips;

	public PeopleService(JdbcClient db, MlClient ml, Jobs jobs, JobWorker worker, MarkdownExporter exporter, EarshotProperties props) {
		this.db = db;
		this.ml = ml;
		this.jobs = jobs;
		this.worker = worker;
		this.exporter = exporter;
		this.clips = props.data().resolve("audio/clips");
	}

	public List<Map<String, Object>> list() {
		return db.sql("""
				SELECT p.id, p.name, p.aliases, p.is_me, p.created_at,
				       (SELECT count(*) FROM voiceprint v WHERE v.person_id = p.id) AS voiceprints,
				       (SELECT string_agg(src || ' ×' || n, ', ') FROM (SELECT source AS src, count(*) AS n FROM voiceprint v
				            WHERE v.person_id = p.id GROUP BY source ORDER BY source) x) AS voiceprint_sources,
				       (SELECT count(DISTINCT sc.conversation_id) FROM speaker_cluster sc WHERE sc.person_id = p.id) AS conversations,
				       (SELECT max(c.local_date) FROM speaker_cluster sc JOIN conversation c ON c.id = sc.conversation_id
				            WHERE sc.person_id = p.id)::text AS last_seen,
				       (SELECT min(c.local_date) FROM speaker_cluster sc JOIN conversation c ON c.id = sc.conversation_id
				            WHERE sc.person_id = p.id)::text AS first_seen
				FROM person p ORDER BY p.is_me DESC, p.name""").query().listOfRows().stream().map(r -> {
			var m = new java.util.LinkedHashMap<String, Object>(r);
			try {
				m.put("aliases", r.get("aliases") == null ? List.of() : List.of((String[]) ((java.sql.Array) r.get("aliases")).getArray()));
			} catch (java.sql.SQLException e) {
				m.put("aliases", List.of());
			}
			return (Map<String, Object>) m;
		}).toList();
	}

	/** Unnamed clusters with enough speech to be worth naming, newest first. */
	public List<Map<String, Object>> unknowns(int limit) {
		return db.sql("""
				SELECT sc.id AS cluster_id, sc.speech_s, sc.conversation_id, c.title, c.local_date::text AS local_date, c.recording_id,
				       r.started_at + make_interval(secs => c.start_offset_s) AS conversation_started_at, r.time_zone,
				       (SELECT json_build_array(s.start_s, s.end_s) FROM segment s WHERE s.speaker_cluster_id = sc.id
				            ORDER BY s.end_s - s.start_s DESC LIMIT 1)::text AS sample
				FROM speaker_cluster sc JOIN conversation c ON c.id = sc.conversation_id JOIN recording r ON r.id = c.recording_id
				WHERE sc.person_id IS NULL AND sc.speech_s >= 3 AND NOT c.is_ambient AND sc.embedding IS NOT NULL
				ORDER BY c.local_date DESC, sc.speech_s DESC LIMIT :n""").param("n", limit).query().listOfRows();
	}

	public long meId() {
		return db.sql("SELECT id FROM person WHERE is_me").query(Long.class).optional()
				.orElseGet(() -> db.sql("INSERT INTO person (name, is_me) VALUES ('Me', true) RETURNING id").query(Long.class).single());
	}

	private long personNamed(String name) {
		return db.sql("SELECT id FROM person WHERE lower(name) = lower(:n) AND NOT is_me").param("n", name).query(Long.class).optional()
				.orElseGet(() -> db.sql("INSERT INTO person (name) VALUES (:n) RETURNING id").param("n", name).query(Long.class).single());
	}

	/** "Speaker 3 → Ravi" (A-6), optionally adding the cluster's embedding to Ravi's voiceprints. */
	@Transactional
	public void assign(long clusterId, Long personId, String newName, boolean me, boolean addToLibrary) {
		long target = me ? meId() : personId != null ? personId : personNamed(newName.trim());
		// The same speaker in every part of a resumed meeting: their first cluster and its continuations.
		db.sql("""
				UPDATE speaker_cluster SET person_id = :p, manual = true
				WHERE id = :id OR id = (SELECT origin_cluster_id FROM speaker_cluster WHERE id = :id)
				   OR origin_cluster_id = COALESCE((SELECT origin_cluster_id FROM speaker_cluster WHERE id = :id), :id)""")
				.param("p", target).param("id", clusterId).update();
		if (addToLibrary) {
			db.sql("""
					INSERT INTO voiceprint (person_id, embedding, source)
					SELECT :p, embedding, CASE WHEN channel = 'system' THEN 'call' ELSE 'recorder' END FROM speaker_cluster
					WHERE id = :id AND embedding IS NOT NULL""").param("p", target).param("id", clusterId).update();
			rematch();
		}
		reexport("SELECT DISTINCT c.local_date FROM conversation c JOIN speaker_cluster sc ON sc.conversation_id = c.id WHERE sc.id = " + clusterId);
	}

	/** Marks recorder segments as the user: the best Me voiceprint, from the recorder's own mic (PRD §7). */
	@Transactional
	public void enrollFromSegments(List<Long> segmentIds) {
		var rows = db.sql("""
				SELECT r.work_path, s.start_s, s.end_s FROM segment s JOIN conversation c ON c.id = s.conversation_id
				JOIN recording r ON r.id = c.recording_id WHERE s.id IN (:ids) ORDER BY r.work_path, s.start_s""")
				.param("ids", segmentIds).query((rs, i) -> new Object[] { rs.getString(1), rs.getDouble(2), rs.getDouble(3) }).list();
		var byFile = new java.util.LinkedHashMap<String, List<double[]>>();
		rows.forEach(r -> byFile.computeIfAbsent((String) r[0], k -> new java.util.ArrayList<>()).add(new double[] { (double) r[1], (double) r[2] }));
		long me = meId();
		byFile.forEach((path, ranges) -> addVoiceprint(me, ml.embed(path, ranges), "recorder"));
		rematch();
	}

	/** A clip recorded in the browser (laptop mic) or uploaded. */
	public void enrollFromClip(MultipartFile file, String source, Long personId) throws IOException {
		Files.createDirectories(clips);
		Path raw = clips.resolve(System.currentTimeMillis() + "-" + (file.getOriginalFilename() == null ? "clip.webm"
				: file.getOriginalFilename().replaceAll("[^A-Za-z0-9._-]", "_")));
		file.transferTo(raw);
		Path work = Path.of(raw + ".flac");
		var prep = ml.prepare(raw.toString(), work.toString());
		var regions = new java.util.ArrayList<double[]>();
		prep.get("regions").forEach(r -> regions.add(new double[] { r.get(0).asDouble(), r.get(1).asDouble() }));
		if (regions.isEmpty()) {
			throw new IllegalArgumentException("No speech found in the clip.");
		}
		long person = personId != null ? personId : meId();
		addVoiceprint(person, ml.embed(work.toString(), regions), "laptop_mic".equals(source) ? "laptop_mic" : "upload");
		rematch();
	}

	private void addVoiceprint(long personId, float[] embedding, String source) {
		db.sql("INSERT INTO voiceprint (person_id, embedding, source) VALUES (:p, CAST(:e AS vector), :s)").param("p", personId)
				.param("e", Vectors.literal(embedding)).param("s", source).update();
		log.info("event=voiceprint_added person={} source={}", personId, source);
	}

	public void rematch() {
		jobs.enqueue("rematch", null, null, null);
		worker.wake();
	}

	@Transactional
	public void update(long id, String name, List<String> aliases) {
		db.sql("UPDATE person SET name = COALESCE(:n, name), aliases = COALESCE(CAST(:a AS text[]), aliases) WHERE id = :id")
				.param("n", name).param("a", aliases == null ? null : "{" + String.join(",", aliases.stream().map(a -> "\"" + a.replace("\"", "") + "\"").toList()) + "}")
				.param("id", id).update();
		reexportPerson(id);
	}

	@Transactional
	public void merge(long from, long into) {
		if (from == into) {
			return;
		}
		db.sql("UPDATE voiceprint SET person_id = :into WHERE person_id = :from").param("into", into).param("from", from).update();
		db.sql("UPDATE speaker_cluster SET person_id = :into WHERE person_id = :from").param("into", into).param("from", from).update();
		db.sql("UPDATE person SET aliases = array_append(aliases, (SELECT name FROM person WHERE id = :from)) WHERE id = :into")
				.param("from", from).param("into", into).update();
		db.sql("DELETE FROM person WHERE id = :from").param("from", from).update();
		reexportPerson(into);
	}

	/** Voiceprints go; their lines show as "Unknown"/"Speaker N" everywhere (PRD §7). */
	@Transactional
	public void delete(long id) {
		var days = days(id);
		db.sql("UPDATE speaker_cluster SET person_id = NULL, manual = false WHERE person_id = :id").param("id", id).update();
		db.sql("DELETE FROM person WHERE id = :id").param("id", id).update();
		days.forEach(exporter::exportDay);
	}

	private List<LocalDate> days(long personId) {
		return db.sql("""
				SELECT DISTINCT c.local_date FROM conversation c JOIN speaker_cluster sc ON sc.conversation_id = c.id
				WHERE sc.person_id = :p""").param("p", personId).query(LocalDate.class).list();
	}

	private void reexportPerson(long personId) {
		days(personId).forEach(exporter::exportDay);
	}

	private void reexport(String sql) {
		db.sql(sql).query(LocalDate.class).list().forEach(exporter::exportDay);
	}
}
