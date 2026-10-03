package me.crema.novelia.account;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

/**
 * Development-only stdin probe (JVM or a separate Android app_process).
 * Not packaged into the app. Input: 4-byte big-endian length + UTF-8 HTML.
 * Output: generated counts/booleans only. Never writes input to disk.
 */
public final class LibraryProbe {
    public static void main(String[] args) {
        if (args.length == 3 && "--listen".equals(args[0])) {
            // Old adbd has no raw stdin channel. An explicitly started, bounded
            // loopback-only process can be reached through a temporary adb forward.
            try {
                int port = Integer.parseInt(args[1]), count = Integer.parseInt(args[2]);
                if (port < 1024 || port > 65535 || count < 1 || count > 10) throw new IOException();
                try (ServerSocket server = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
                    server.setSoTimeout(120000);
                    System.out.println("{\"ready\":true}");
                    for (int i = 0; i < count; i++) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(60000);
                            DataInputStream in = new DataInputStream(socket.getInputStream());
                            String result = inspect(in.readUTF(), in);
                            PrintWriter out = new PrintWriter(socket.getOutputStream());
                            out.println(result);
                            out.flush();
                        }
                    }
                }
            } catch (Throwable ignored) {
                System.out.println("{\"ok\":false}");
                System.exit(1);
            }
            return;
        }
        if (args.length != 1) {
            System.out.println("{\"ok\":false}");
            System.exit(1);
        }
        String result = inspect(args[0], new DataInputStream(System.in));
        System.out.println(result);
        if ("{\"ok\":false}".equals(result)) System.exit(1);
    }

    private static String inspect(String url, DataInputStream input) {
        try {
            final String target = LibraryParser.normalizeUrl(url);
            int size = input.readInt();
            if (size <= 0 || size > 2 * 1024 * 1024) throw new IOException();
            byte[] bytes = new byte[size];
            input.readFully(bytes);
            final String html = new String(bytes, "UTF-8");
            AccountClient client = new AccountClient(new AccountClient.HttpAgent() {
                @Override public String get(String url) throws IOException {
                    if (!target.equals(url)) throw new IOException();
                    return html;
                }
                @Override public String post(String url, Map<String, String> form) throws IOException {
                    throw new IOException();
                }
            });
            LibraryPage page = client.library(target);
            int continuations = 0;
            int readCounts = 0, totalCounts = 0;
            for (LibraryPage.Item item : page.items)
                {
                    if (item.continueUrl.length() != 0) continuations++;
                    if (item.lastReadEpisode >= 0) readCounts++;
                    if (item.totalEpisodes >= 0) totalCounts++;
                }
            return "{\"ok\":true,\"count\":" + page.items.size()
                    + ",\"page\":" + page.page + ",\"continuations\":" + continuations
                    + ",\"readCounts\":" + readCounts + ",\"totalCounts\":" + totalCounts
                    + ",\"sortOptions\":" + page.sorts.size() + ",\"groupOptions\":" + page.groups.size()
                    + ",\"previous\":" + (page.previousUrl.length() != 0)
                    + ",\"next\":" + (page.nextUrl.length() != 0) + "}";
        } catch (Throwable ignored) {
            // Even parser errors and exception causes must not print input.
            return "{\"ok\":false}";
        }
    }
}
