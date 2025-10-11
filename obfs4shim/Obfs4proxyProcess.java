package obfs4shim;


import java.io.*;
import java.util.Map;
import java.util.regex.Pattern;


public final class Obfs4proxyProcess {
	public final Process process;
	public final int port;


	public Obfs4proxyProcess(String command, Map<String, String> environment)
	throws IOException {
		var pb = new ProcessBuilder(command.split("[ \t\n]"));
		var env = pb.environment();
		env.clear();
		env.putAll(environment);

		pb.redirectError(ProcessBuilder.Redirect.INHERIT);
		// We can't redirect stdout because we need to read it.
		// We'll copy the stream to our stdout.

		process = pb.start();
		port = awaitPortNumber(process);
	}


	/// Parses the stdout from `obfs4proxy` and looks for the port number
	/// it reports to have bound to.
	private static int awaitPortNumber(Process obfs4proxyProc)
	throws IOException {
		var stdout = obfs4proxyProc.getInputStream();
		var reader = new BufferedReader(new InputStreamReader(stdout));

		/*
		Example stdout from obfs4proxy.
		VERSION 1
		CMETHOD obfs4 socks5 127.0.0.1:35265
		CMETHODS DONE
		*/
		String line;
		int port = -1;
		var pat = Pattern.compile("CMETHOD obfs4 socks5 127\\.0\\.0\\.1:(\\d+)");

		while ((line = reader.readLine()) != null) {
			// Forward to our stdout.
			System.out.println(line);

			var m = pat.matcher(line);
			if (m.matches()) {
				port = Integer.parseInt(m.group(1));
				break;
			}
		}

		if (port == -1) throw new RuntimeException("No obfs4proxy port found.");

		final var parent = Thread.currentThread();
		// Continue forwarding anything else.
		Thread.startVirtualThread(() -> {
			try {
				reader.transferTo(new OutputStreamWriter(System.out));
			}
			catch (Exception e) {
				// This would be truely exceptional:
				// if the process exits, the stdout is closed
				// and transferTo completes normally.
				e.printStackTrace();
				obfs4proxyProc.destroy();
				parent.interrupt();
			}
		});

		return port;
	}
}
