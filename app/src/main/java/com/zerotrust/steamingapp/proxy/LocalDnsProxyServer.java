package com.zerotrust.steamingapp.proxy;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;

/**
 * Micro-proxy locale che intercetta il traffico della WebView
 * e risolve tutti i domini tramite DNS-over-HTTPS (DoH Cloudflare),
 * aggirando in modo trasparente i DNS sinkhole degli ISP (127.0.0.1).
 */
public class LocalDnsProxyServer {

    private static final String TAG = "LocalDnsProxy";
    private ServerSocket serverSocket;
    private int port = -1;
    private boolean isRunning = false;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private DnsOverHttps dnsOverHttps;

    public void start() throws Exception {
        // Inizializza il resolver DNS-over-HTTPS (Cloudflare 1.1.1.1)
        OkHttpClient bootstrapClient = new OkHttpClient.Builder().build();
        dnsOverHttps = new DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(HttpUrl.get("https://cloudflare-dns.com/dns-query"))
                .bootstrapDnsHosts(
                        InetAddress.getByName("1.1.1.1"),
                        InetAddress.getByName("1.0.0.1")
                )
                .includeIPv6(false)
                .build();

        // Si mette in ascolto su porta libera su localhost (127.0.0.1)
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        port = serverSocket.getLocalPort();
        isRunning = true;
        Log.i(TAG, "Proxy DoH locale avviato su 127.0.0.1:" + port);

        executor.execute(() -> {
            while (isRunning && !serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executor.execute(() -> handleClient(clientSocket));
                } catch (Exception e) {
                    if (isRunning) {
                        Log.e(TAG, "Errore accept client proxy", e);
                    }
                }
            }
        });
    }

    public int getPort() {
        return port;
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
        executor.shutdownNow();
        Log.i(TAG, "Proxy DoH locale arrestato.");
    }

    private void handleClient(Socket clientSocket) {
        try {
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.ISO_8859_1));
            String initialLine = reader.readLine();
            if (initialLine == null || initialLine.isEmpty()) {
                clientSocket.close();
                return;
            }

            String[] parts = initialLine.split(" ");
            if (parts.length < 2) {
                clientSocket.close();
                return;
            }

            String method = parts[0];
            String target = parts[1];

            // Consuma gli header HTTP della richiesta fino alla riga vuota
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                // Header consumati
            }

            String host;
            int targetPort;

            if ("CONNECT".equalsIgnoreCase(method)) {
                // Tunnel HTTPS (CONNECT host:port HTTP/1.1)
                String[] hostPort = target.split(":");
                host = hostPort[0];
                targetPort = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 443;
            } else {
                // Richiesta HTTP ordinaria (GET http://host/path HTTP/1.1)
                java.net.URI uri = new java.net.URI(target.startsWith("http") ? target : "http://" + target);
                host = uri.getHost();
                targetPort = uri.getPort() != -1 ? uri.getPort() : 80;
            }

            // Risoluzione DoH del dominio per bypassare il sinkhole 127.0.0.1
            InetAddress resolvedAddress = resolveDns(host);
            if (resolvedAddress == null) {
                Log.w(TAG, "Impossibile risolvere " + host);
                clientSocket.close();
                return;
            }

            // Connessione all'IP reale di destinazione
            Log.i(TAG, "Connessione proxy (" + method + ") verso " + host + ":" + targetPort + " via IP " + resolvedAddress);
            Socket targetSocket = new Socket(resolvedAddress, targetPort);
            InputStream targetIn = targetSocket.getInputStream();
            OutputStream targetOut = targetSocket.getOutputStream();

            if ("CONNECT".equalsIgnoreCase(method)) {
                // Risponde al client con 200 Connection Established per avviare l'handshake TLS diretto
                String response = "HTTP/1.1 200 Connection Established\r\nProxy-Agent: LocalDnsProxy\r\n\r\n";
                clientOut.write(response.getBytes(StandardCharsets.ISO_8859_1));
                clientOut.flush();
            } else {
                // Riscrive la richiesta HTTP e la inoltra
                String forward = initialLine + "\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
                targetOut.write(forward.getBytes(StandardCharsets.ISO_8859_1));
                targetOut.flush();
            }

            // Relay bidirezionale dei dati
            executor.execute(() -> pipe(clientIn, targetOut, clientSocket, targetSocket));
            pipe(targetIn, clientOut, targetSocket, clientSocket);

        } catch (Exception e) {
            try {
                clientSocket.close();
            } catch (Exception ignored) {}
        }
    }

    private InetAddress resolveDns(String host) {
        try {
            // Se è già un IP numerico o localhost
            if (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")) {
                return InetAddress.getByName(host);
            }

            // Risoluzione crittografata con DoH (ignora i DNS dell'ISP)
            List<InetAddress> addresses = dnsOverHttps.lookup(host);
            if (addresses != null && !addresses.isEmpty()) {
                return addresses.get(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "DoH lookup fallito per: " + host + ", fallback a DNS sistema", e);
        }

        try {
            return InetAddress.getByName(host);
        } catch (Exception e) {
            return null;
        }
    }

    private void pipe(InputStream in, OutputStream out, Socket sockIn, Socket sockOut) {
        try {
            byte[] buffer = new byte[16384];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { sockIn.close(); } catch (Exception ignored) {}
            try { sockOut.close(); } catch (Exception ignored) {}
        }
    }
}
