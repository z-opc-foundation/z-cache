package com.zifang.z.cache.server;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 容器健康检查入口，不依赖 redis-cli 或其他外部工具。
 */
public final class HealthCheck {
    private HealthCheck() {
    }

    public static void main(String[] args) throws Exception {
        int port = 6379;
        String password = null;
        String passwordFile = null;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--password".equals(args[i]) && i + 1 < args.length) {
                password = args[++i];
            } else if ("--password-file".equals(args[i]) && i + 1 < args.length) {
                passwordFile = args[++i];
            }
        }
        if (passwordFile != null) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(passwordFile))) {
                password = reader.readLine();
            }
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            socket.setSoTimeout(2000);
            OutputStream output = socket.getOutputStream();
            if (password != null && !password.isEmpty()) {
                byte[] passwordBytes = password.getBytes("UTF-8");
                String auth = "*2\r\n$4\r\nAUTH\r\n$" + passwordBytes.length + "\r\n" + password + "\r\n";
                output.write(auth.getBytes("UTF-8"));
            }
            output.write("*1\r\n$4\r\nPING\r\n".getBytes("UTF-8"));
            output.flush();
            InputStream input = socket.getInputStream();
            if (password != null && !password.isEmpty() && !"+OK\r\n".equals(read(input, 5))) {
                System.exit(1);
            }
            if (!"+PONG\r\n".equals(read(input, 7))) {
                System.exit(1);
            }
        }
    }

    private static String read(InputStream input, int length) throws Exception {
        byte[] response = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(response, offset, length - offset);
            if (count < 0) {
                return "";
            }
            offset += count;
        }
        return new String(response, "UTF-8");
    }
}