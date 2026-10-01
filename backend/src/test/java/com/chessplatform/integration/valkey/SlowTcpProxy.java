package com.chessplatform.integration.valkey;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

/**
 * A TCP proxy that can be slow on purpose. Stands in for what the first ECS deployment hit: a
 * Valkey connection whose initialisation (TLS handshake + HELLO on a CPU-starved JVM) takes
 * longer than a second.
 *
 * <ul>
 *   <li>{@code handshakeDelay}: every new connection's first client bytes are held this long
 *       before being forwarded — the connection is open, the server just "answers late".</li>
 *   <li>{@link #stall()}: from now on nothing is forwarded either way — a server that holds
 *       connections open and answers nothing (the outage shape ADR-018 is built for).</li>
 * </ul>
 */
final class SlowTcpProxy implements AutoCloseable {

    private final ServerSocket server;
    private final String upstreamHost;
    private final int upstreamPort;
    private final Duration handshakeDelay;
    private volatile boolean stalled;

    SlowTcpProxy(String upstreamHost, int upstreamPort, Duration handshakeDelay) throws IOException {
        this.server = new ServerSocket();
        this.server.bind(new InetSocketAddress("127.0.0.1", 0));
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.handshakeDelay = handshakeDelay;
        Thread.ofVirtual().start(this::acceptLoop);
    }

    int port() {
        return server.getLocalPort();
    }

    void stall() {
        stalled = true;
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Socket upstream = new Socket(upstreamHost, upstreamPort);
                Thread.ofVirtual().start(() -> pipe(client, upstream, true));
                Thread.ofVirtual().start(() -> pipe(upstream, client, false));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void pipe(Socket from, Socket to, boolean delayFirstBytes) {
        byte[] buffer = new byte[8192];
        boolean first = delayFirstBytes;
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (first) {
                    Thread.sleep(handshakeDelay);
                    first = false;
                }
                while (stalled) {
                    Thread.sleep(50);
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            // connection closed by either side
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
