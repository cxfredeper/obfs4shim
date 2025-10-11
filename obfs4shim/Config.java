package obfs4shim;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import obfs4shim.MapUtils;


public class Config {
	static Map<String, String> config = null;
	private static final Set<String> REQUIRED_CONFIG_KEYS = Set.of(
		"cert",
		"iat_mode",
		"remote",
		"remote_port",
		"obfs4proxy_dir",
		"obfs4proxy_cmd");
	private static final Map<String, String> CONFIG_DEFAULTS = Map.of(
		"local_port", "1080",
		"debug_level", "1");


	@SuppressWarnings("unchecked")
	public static void loadConfig(String path) throws IOException {
		var props = new Properties();
		try (var reader = Files.newBufferedReader(Path.of(path))) {
			props.load(reader);
		}
		// I hate you Properties.
		var cast = (Map<String, String>) (Object) props;
		config = Map.copyOf(MapUtils.mergeMaps(cast, CONFIG_DEFAULTS));
		validateConfig(config);
	}


	public static Map<String, String> validateConfig(Map<String, String> config) {
		for (var key : REQUIRED_CONFIG_KEYS) {
			if (!config.containsKey(key))
				throw new NoSuchElementException("Missing required config key: " + key);
		}
		return config;
	}


	public static String get(String key) {
		if (config == null)
			throw new NullPointerException("No config loaded");
		return config.get(key);
	}
}
