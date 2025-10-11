package obfs4shim;


import java.util.ArrayList;
import java.util.StringJoiner;
import java.nio.charset.StandardCharsets;


public final class Logging {
	/// This is the _debug level_,
	/// with levels at lower values including lower-level details
	/// and vice versa.
	public static int level = 0;
	public static final int
	DEBUG = 0,
	INFO  = 1,
	WARN  = 2,
	ERROR = 3;

	public static void debug(String msg) {
		if (level <= DEBUG)
			System.err.println("[DEBUG]\t" + msg);
	}

	public static void debugf(String format, Object... args) {
		debug(String.format(format, args));
	}

	public static void info(String msg) {
		if (level <= INFO)
			System.err.println("[INFO]\t" + msg);
	}

	public static void infof(String format, Object... args) {
		info(String.format(format, args));
	}

	public static void warn(String msg) {
		if (level <= WARN)
			System.err.println("[WARN]\t" + msg);
	}

	public static void error(String msg) {
		if (level <= ERROR)
			System.err.println("[ERROR]\t" + msg);
	}

	public static String formatBytes(byte[] buffer, int offset, int len) {
		var hexs = new ArrayList<String>();
		for (int i = offset; i < len; ++i)
			hexs.add(String.format("%02x", buffer[i]));
		var sj = new StringJoiner(", ");
		hexs.forEach(sj::add);
		return sj.toString();
	}

	public static String asciiBytes(byte[] buffer, int offset, int len) {
		byte[] data = new byte[len - offset];
		System.arraycopy(buffer, offset, data, 0, len);
		for (int i = 0; i < data.length; ++i) {
			int c = data[i];
			if (c <= 0x1F || c >= 0x7F)
				data[i] = '.';
		}
		return new String(data, StandardCharsets.UTF_8);
	}
}
