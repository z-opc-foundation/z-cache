package com.zifang.z.cache.core.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main entry point for z-cache server
 */
public class ZCacheServerMain {

    private static final Logger logger = LogManager.getLogger(ZCacheServerMain.class);

    public static void main(String[] args) {
        String host = System.getProperty("zcache.host", "0.0.0.0");
        String password = System.getenv("ZCACHE_PASSWORD");
        if (password == null || password.isEmpty()) {
            password = System.getProperty("zcache.password");
        }
        int port = RedisServer.DEFAULT_PORT;
        int maxEntries = Integer.getInteger("zcache.max-entries", 0);

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    if (i + 1 >= args.length) {
                        fail("--host requires an argument");
                    }
                    host = args[++i];
                    break;
                case "--password":
                    if (i + 1 >= args.length) {
                        fail("--password requires an argument");
                    }
                    password = args[++i];
                    break;
                case "--password-file":
                    if (i + 1 >= args.length) {
                        fail("--password-file requires an argument");
                    }
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(args[++i]))) {
                        password = reader.readLine();
                    } catch (java.io.IOException e) {
                        fail("Cannot read password file: " + e.getMessage());
                    }
                    break;
                case "--max-entries":
                    if (i + 1 >= args.length) {
                        fail("--max-entries requires an argument");
                    }
                    try {
                        maxEntries = Integer.parseInt(args[++i]);
                        if (maxEntries < 0) {
                            fail("max-entries cannot be negative");
                        }
                    } catch (NumberFormatException e) {
                        fail("Invalid max-entries: " + args[i]);
                    }
                    break;
                case "--port":
                case "-p":
                    if (i + 1 < args.length) {
                        try {
                            port = Integer.parseInt(args[++i]);
                            if (port < 1 || port > 65535) {
                                System.err.println("Error: Port must be between 1 and 65535");
                                System.exit(1);
                            }
                        } catch (NumberFormatException e) {
                            System.err.println("Error: Invalid port number: " + args[i]);
                            System.exit(1);
                        }
                    } else {
                        System.err.println("Error: --port requires an argument");
                        System.exit(1);
                    }
                    break;
                case "--help":
                case "-h":
                    printHelp();
                    System.exit(0);
                    break;
                default:
                    System.err.println("Error: Unknown option: " + args[i]);
                    printHelp();
                    System.exit(1);
            }
        }

        // Start server
        logger.info("Starting z-cache server...");
        logger.info("Version: 0.1.0 (MVP)");
        logger.info("Listening on {}:{} (max entries: {})", host, port, maxEntries);

        RedisServer server = new RedisServer(host, port, maxEntries, password);

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received, stopping server...");
            server.stop();
        }));

        // Run server (blocks until shutdown)
        try {
            server.start();
        } catch (InterruptedException e) {
            logger.info("Server interrupted, shutting down...");
            Thread.currentThread().interrupt();
        }

        logger.info("z-cache server stopped");
    }

    private static void fail(String message) {
        System.err.println("Error: " + message);
        System.exit(1);
    }

    private static void printHelp() {
        System.out.println("z-cache - A Redis-compatible in-memory cache server");
        System.out.println();
        System.out.println("Usage: java -jar z-cache-core.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --host <host>        Bind address (default: 0.0.0.0)");
        System.out.println("  --password <value>   Enable AUTH password protection");
        System.out.println("  --password-file <f>  Read AUTH password from file");
        System.out.println("  -p, --port <port>    Listen port (default: 6379)");
        System.out.println("  --max-entries <n>    Maximum keys, 0 means unlimited");
        System.out.println("  -h, --help           Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar z-cache-core.jar");
        System.out.println("  java -jar z-cache-core.jar --port 6380");
    }
}
