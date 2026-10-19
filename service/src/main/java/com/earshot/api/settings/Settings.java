package com.earshot.api.settings;

import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.earshot.api.config.EarshotProperties;

import tools.jackson.databind.json.JsonMapper;

/** Runtime settings (PRD §9.1): defaults from application.yaml, overrides in the setting table. */
@Service
public class Settings {

	private final JdbcClient db;
	private final JsonMapper json;
	private final Map<String, Object> defaults;

	public Settings(JdbcClient db, JsonMapper json, EarshotProperties props) {
		this.db = db;
		this.json = json;
		this.defaults = props.defaults();
	}

	public Map<String, Object> all() {
		Map<String, Object> out = new TreeMap<>(defaults);
		db.sql("SELECT key, value_json::text AS v FROM setting").query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2)))
				.list().forEach(e -> out.put(e.getKey(), json.readValue(e.getValue(), Object.class)));
		return out;
	}

	public void put(String key, Object value) {
		if (!defaults.containsKey(key)) {
			throw new IllegalArgumentException("unknown setting " + key);
		}
		db.sql("""
				INSERT INTO setting (key, value_json, updated_at) VALUES (:k, CAST(:v AS jsonb), now())
				ON CONFLICT (key) DO UPDATE SET value_json = EXCLUDED.value_json, updated_at = now()""")
				.param("k", key).param("v", json.writeValueAsString(value)).update();
	}

	private Object raw(String key) {
		return db.sql("SELECT value_json::text FROM setting WHERE key = :k").param("k", key).query(String.class)
				.optional().<Object>map(v -> json.readValue(v, Object.class)).orElseGet(() -> defaults.get(key));
	}

	public String str(String key) {
		Object v = raw(key);
		return v == null ? null : v.toString();
	}

	public double num(String key) {
		Object v = raw(key);
		return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v));
	}
}
