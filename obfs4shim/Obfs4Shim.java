package obfs4shim;

import java.net.*;
import java.io.*;
import java.util.*;

import obfs4shim.Config;
import obfs4shim.Logging;
import obfs4shim.MapUtils;
import obfs4shim.Obfs4proxyProcess;

import static obfs4shim.Logging.info;
import static obfs4shim.Logging.infof;
import static obfs4shim.Logging.debug;
import static obfs4shim.Logging.debugf;


public class Obfs4Shim {
	private static final Map<String, String> DEFAULT_ENV_VARS = Map.of(
		"TOR_PT_MANAGED_TRANSPORT_VER", "1",
		"TOR_PT_CLIENT_TRANSPORTS", "obfs4",
		"OBFS4SHIM_CONFIG_PATH", "obfs4shim.properties");


	public static void main(String... args) throws IOException {
		// Initialize env and config.
		var env = MapUtils.mergeMaps(System.getenv(), DEFAULT_ENV_VARS);
		var configPath = env.get("OBFS4SHIM_CONFIG_PATH");
		Config.loadConfig(configPath);
		Logging.level = Integer.parseInt(Config.get("debug_level"));

		info("Loaded configuration: " + configPath);

		var obfs4proxyCmd = Config.get("obfs4proxy_cmd");
		int shimListenPort = Integer.parseInt(Config.get("local_port"));
		assert shimListenPort < 0xFFFF;

		info("Starting obfs4proxy: " + obfs4proxyCmd);
		env.putIfAbsent("TOR_PT_STATE_LOCATION", Config.get("obfs4proxy_dir"));
		info("TOR_PT_STATE_LOCATION: " + env.get("TOR_PT_STATE_LOCATION"));

		var obfs4proxy = new Obfs4proxyProcess(obfs4proxyCmd, env);

		info("Started obfs4proxy; port " + obfs4proxy.port);

		ServerSocket serverSocket = new ServerSocket();
		// We only listen on localhost.
		serverSocket.bind(
			new InetSocketAddress(InetAddress.getLoopbackAddress(), shimListenPort));

		info("Listening on port " + shimListenPort);

		while (true) {
			Socket client = serverSocket.accept();
			handleClient(client, obfs4proxy.port);
		}
	}


	private record StreamContext(int clientPort, boolean inboundDirection) {}

	private static void handleClient(Socket client, int obfs4Port)
	throws IOException {
		Socket obfs4Socket;
		int clientPort = client.getPort();

		infof("new client from port %d: starting obfs4proxy handshake...",
			clientPort);
		try { obfs4Socket = obfs4proxyHandshake(obfs4Port); }
		catch (ConnectException | SocksHandshakeException e) {
			Logging.error("obfs4proxy handshake failed: " + e.toString());
			if (Logging.level <= Logging.DEBUG)
				e.printStackTrace();
			client.close();
			return;
		}

		infof("new client from port %d: obfs4proxy handshake successful",
			clientPort);

		// Forward client to obfs4.
		Thread.startVirtualThread(() -> {
			forwardStream(client, obfs4Socket,
			//forwardStreamViaTransferTo(client, obfs4Socket,
				new StreamContext(clientPort, false));
		});

		// Forward obfs4 to client.
		Thread.startVirtualThread(() -> {
			forwardStream(obfs4Socket, client,
			//forwardStreamViaTransferTo(obfs4Socket, client,
				new StreamContext(clientPort, true));
		});
	}


	/// This essentially does the same thing as `InputStream.transferTo`,
	/// but we can do some logging inside.
	private static void
	forwardStream(Socket source, Socket dest, StreamContext ctx) {
		info("Starting forward " + formatForwardArrow(source, dest, ctx));

		try {
			InputStream in;
			OutputStream out;
			try {
				in = source.getInputStream();
				out = dest.getOutputStream();
			}
			catch (IOException e) { throw new UncheckedIOException(e); }

			byte[] buffer = new byte[0x3FFF];  // 16 KiB
			int bytesRead;
			try {
				while ((bytesRead = in.read(buffer)) != -1) {
					out.write(buffer, 0, bytesRead);
					//out.flush();
					debugf("%s %6d bytes %s",
						ctx.inboundDirection ? "recv" : "sent",
						bytesRead, formatForwardArrow(source, dest, ctx));
					//debugf("(%d) content: %s",
					//	ctx.clientPort(), Logging.asciiBytes(buffer, 0, bytesRead));
				}
			}
			catch (IOException e) {
				Logging.infof(
					"(%d) %s socket failed: %s",
					ctx.clientPort(), ctx.inboundDirection() ? "Inbound" : "Outbound",
					e.toString());
				if (Logging.level <= Logging.DEBUG)
					e.printStackTrace();
			}
		}
		finally {
			info("Stopped forward " + formatForwardArrow(source, dest, ctx));
		}
	}


	private static void
	forwardStreamViaTransferTo(Socket source, Socket dest, StreamContext ctx) {
		info("Starting forward " + formatForwardArrow(source, dest, ctx));
		try {
			source.getInputStream().transferTo(dest.getOutputStream());
		}
		catch (IOException e) {
			Logging.infof(
				"(%d) %s socket failed: %s",
				ctx.clientPort(), ctx.inboundDirection() ? "Inbound" : "Outbound",
				e.toString());
			if (Logging.level <= Logging.DEBUG)
				e.printStackTrace();
		}
		finally {
			info("Stopped forward " + formatForwardArrow(source, dest, ctx));
		}
	}


	private static String
	formatForwardArrow(Socket source, Socket dest, StreamContext ctx) {
		Socket left, right;
		String arrow;
		if (ctx.inboundDirection()) {
			arrow = "->";
			left = source;
			right = dest;
		}
		else {
			arrow = "<-";
			left = dest;
			right = source;
		}
		return new StringJoiner(" ")
			.add(left.getInetAddress().getHostAddress())
			.add(Integer.toString(left.getPort()))
			.add(arrow)
			.add(right.getInetAddress().getHostAddress())
			.add(Integer.toString(right.getPort()))
			.toString();
	}


	private static Socket obfs4proxyHandshake(int obfs4Port)
	throws IOException {
		var localhost = InetAddress.getLoopbackAddress();
		var obfs4Socket = new Socket(localhost, obfs4Port);

		var in = obfs4Socket.getInputStream();
		var out = obfs4Socket.getOutputStream();

		// The client connects to the server, and sends a version
		// identifier/method selection message:
		// VER 5, 1 method, 0x02 for user/pass.
		out.write(new byte[] { 0x05, 0x01, 0x02 });
		out.flush();
		debug("obfs4proxy handshake: sent method selection request");


		// The server selects from one of the methods given in METHODS, and
		// sends a METHOD selection message:
		// Expected: VER 5, user/pass.
		if (!Arrays.equals(in.readNBytes(2), new byte[] { 0x5, 0x2 }))
			throw new SocksHandshakeException(
				"obfs4proxy did not accept user/pass authentication method.");
		debug("obfs4proxy handshake: recv method selection reply");


		// Once the SOCKS V5 server has started, and the client has selected the
		// Username/Password Authentication protocol, the Username/Password
		// subnegotiation begins.  This begins with the client producing a
		// Username/Password request:
		// For obfs4proxy handshake, this is the cert+iat-mode data.
		var cert = Config.get("cert");
		var iat = Config.get("iat_mode");
		byte[] user = (cert + ";" + iat).getBytes("UTF-8");
		assert user.length < 256;

		out.write(concatBytes(
				// VER 1 of subnegotiation, username len, username...
				new byte[] { 0x01, (byte) user.length }, user,
				// password len of 1, null password.
				new byte[] { 0x01, 0x00 }));
		out.flush();
		debug("obfs4proxy handshake: sent user/pass request");


		// The server verifies the supplied UNAME and PASSWD, and sends the
		// following response:
		// VER 1, 0x00 indicates success.
		if (!Arrays.equals(in.readNBytes(2), new byte[] { 0x01, 0x00 }))
			throw new SocksHandshakeException("obfs4proxy user/pass authentication failed.");
		debug("obfs4proxy handshake: user/pass accepted");


		// The SOCKS request is formed as follows:
		// Here we provide the address of the obfs4 peer.
		var remote = InetAddress.ofLiteral(Config.get("remote"));
		byte atype = switch (remote) {
			case Inet4Address _ -> 0x01;
			case Inet6Address _ -> 0x04;
			default -> throw new UnsupportedOperationException(remote.toString());
		};

		int remotePort = Integer.parseInt(Config.get("remote_port"));
		assert remotePort < 0xFFFF;

		byte[] request = concatBytes(
			// VER 5, CMD 0x01 connect, RSV 0x00, ATYP 0x01 IP V4 or 0x04 IP V6
			new byte[] { 0x05, 0x01, 0x00, atype },
			// DST.ADDR
			remote.getAddress(),
			// DST.PORT
			new byte[] { (byte) (remotePort >> 8), (byte) (remotePort & 0xFF) });
		out.write(request);
		out.flush();
		debug("obfs4proxy handshake: sent SOCKS CONNECT request");


		byte[] reply = in.readNBytes(4);
		checkReply(reply[1]);
		int replySize = switch (reply[3]) {
			case 0x01 -> 4 + 4 + 2;  // IPv4
			case 0x04 -> 4 + 16 + 2; // IPv6
			default -> throw new UnsupportedOperationException();
		};
		// We don't care about BND.ADDR and BND.PORT.
		in.readNBytes(replySize - 4);
		debug("obfs4proxy handshake: recv SOCKS CONNECT reply");

		return obfs4Socket;
	}


	private static byte[] concatBytes(byte[]... arrs) {
		int length = 0;
		for (var arr : arrs)
			length += arr.length;

		byte[] dest = new byte[length];
		int offset = 0;
		for (var arr : arrs) {
			System.arraycopy(arr, 0, dest, offset, arr.length);
			offset += arr.length;
		}

		return dest;
	}


	private static void checkReply(byte rep) {
		/*
		o  REP    Reply field:
		   o  X'00' succeeded
		   o  X'01' general SOCKS server failure
		   o  X'02' connection not allowed by ruleset
		   o  X'03' Network unreachable
		   o  X'04' Host unreachable
		   o  X'05' Connection refused
		   o  X'06' TTL expired
		   o  X'07' Command not supported
		   o  X'08' Address type not supported
		   o  X'09' to X'FF' unassigned
		*/
		if (rep != 0)
			throw new SocksHandshakeException(
				switch (rep) {
					case 0x01 -> "General SOCKS server failure.";
					case 0x02 -> "Connection not allowed by ruleset.";
					case 0x03 -> "Network unreachable.";
					case 0x04 -> "Host unreachable.";
					case 0x05 -> "Connection refused.";
					case 0x06 -> "TTL expired.";
					case 0x07 -> "Command not supported.";
					case 0x08 -> "Address type not supported.";
					default   -> "Unassigned reply status.";
			});
	}
}


@SuppressWarnings("serial")
class SocksHandshakeException extends RuntimeException {
	SocksHandshakeException(String msg) { super(msg); }
}
