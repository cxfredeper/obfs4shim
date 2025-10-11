# obfs4proxy shim

## Motivation

The `obfs4proxy` program provides obfuscation of TCP connections
and exposes a SOCKS 5 interface.
It expects the `cert` and `iat-mode` information to be provided
though the user/pass negotiation,
and forwards all traffic to the remote after the SOCKS handshake is completed.

For programs that do not natively support authenticated SOCKS proxy
(e.g. Firefox), there is not way to pass that information.
Additionally, for programs which do support SOCKS 5 user/pass authentication,
it is no longer possible to use the SOCKS protocol,
as the initial SOCKS handshake would have already been consumed by obfs4proxy.


## Description

This program sits in between the client application and obfs4proxy:
to the local client,
it behaves as listening socket that forwards data as-is
to whatever process the remote obfs4proxy is configured to feed to;
to the obfs4proxy process,
it behaves as a connecting client that gives `cert` and `iat-mode` as expected.
Once both sides are connected, it forwards both sockets to each other.


## Usage

For typical web-browsing, we can use the following setup:

```
            REMOTE                            LOCAL                   
       ┌───────────────┐      ┌──────────────────────────────────────┐
       │   obfs4proxy ◄┼──────┼► obfs4proxy ─── obfs4shim ─── browser│
WEB ◄──┼─ SOCKS server │      │                                      │
       └───────────────┘      └──────────────────────────────────────┘
```

On a remote server,
we run an obfs4proxy in server mode
with its `TOR_PT_ORPORT` configured to feed to a SOCKS server.
On the local machine,
we run obfs4shim after configuring it with the remote's address
and the server obfs4proxy instance's `cert` and `iat-mode` values.
The browser can then be configured to use the obfs4shim as the SOCKS proxy;
the handshake will be passed through the obfs4 tunnel to be processed
by the SOCKS server on remote.
