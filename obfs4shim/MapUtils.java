package obfs4shim;


import java.util.Map;
import java.util.HashMap;


public final class MapUtils {
	/// Merges the entries in `src` into a copy of `dest`,
	/// keeping the existing entries from `dest` in case of conflict.
	public static <K, V> Map<K, V>
	mergeMaps(Map<K, V> dest, Map<? extends K, ? extends V> src) {
		var result = new HashMap<K, V>(dest);
		for (var entry : src.entrySet())
			result.putIfAbsent(entry.getKey(), entry.getValue());
		return result;
	}
}
