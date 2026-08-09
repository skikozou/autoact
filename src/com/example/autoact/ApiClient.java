package com.example.autoact;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

// One per accepted connection. Reads a single NDJSON line, dispatches, writes
// a single NDJSON line, closes. No keep-alive.
public class ApiClient extends Thread {

    private final AutomationService svc;
    private final Socket sock;

    public ApiClient(AutomationService svc, Socket sock) {
        this.svc = svc;
        this.sock = sock;
        setName("AutoActApiClient");
        setDaemon(true);
    }

    @Override
    public void run() {
        String resp;
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), "UTF-8"));
            String line = r.readLine();
            if (line == null) line = "";
            resp = ApiHandler.handle(svc, line);
        } catch (Throwable t) {
            Log.w(AutomationService.TAG, "API client read err: " + t);
            resp = "{\"ok\":false,\"error\":\"read failed\"}";
        }
        try {
            OutputStream os = sock.getOutputStream();
            os.write(resp.getBytes("UTF-8"));
            os.write('\n');
            os.flush();
        } catch (Throwable t) {
            Log.w(AutomationService.TAG, "API client write err: " + t);
        }
        try { sock.close(); } catch (Throwable ignored) {}
    }
}
