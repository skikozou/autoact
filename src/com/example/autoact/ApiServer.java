package com.example.autoact;

import android.util.Log;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

// TCP loopback (127.0.0.1) server. One thread per accepted connection.
// Cross-app abstract Unix sockets are blocked by SELinux (untrusted_app)
// on Android 9+, so we bind on TCP loopback instead.
public class ApiServer extends Thread {

    public static final int PORT = 8765;

    private final AutomationService svc;
    private volatile ServerSocket server;
    private volatile boolean running = true;

    public ApiServer(AutomationService svc) {
        this.svc = svc;
        setName("AutoActApiServer");
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            server = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
            Log.i(AutomationService.TAG, "API bound 127.0.0.1:" + PORT);
        } catch (Throwable t) {
            Log.e(AutomationService.TAG, "API bind failed: " + t);
            return;
        }
        while (running) {
            Socket cli;
            try {
                cli = server.accept();
            } catch (Throwable t) {
                if (running) Log.w(AutomationService.TAG, "API accept err: " + t);
                break;
            }
            if (cli == null) continue;
            ApiClient c = new ApiClient(svc, cli);
            c.start();
        }
        try { if (server != null) server.close(); } catch (Throwable ignored) {}
        Log.i(AutomationService.TAG, "API server exited");
    }

    public void shutdown() {
        running = false;
        try { if (server != null) server.close(); } catch (Throwable ignored) {}
        interrupt();
    }
}
