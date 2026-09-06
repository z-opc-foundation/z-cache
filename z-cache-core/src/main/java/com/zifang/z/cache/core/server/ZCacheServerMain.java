package com.zifang.z.cache.core.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main entry point for z-cache server
 */
public class ZCacheServerMain {

    private static final Logger logger = LogManager.getLogger(ZCacheServerMain.class);

    public static void main(String[] args) {
        // Parse command line arguments
        int port = RedisServer.DEFAULT_PORT;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
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
        logger.info("Listening on port: {}", port);

        RedisServer server = new RedisServer(port);

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

    private static void printHelp() {
        System.out.println("z-cache - A Redis-compatible in-memory cache server");
        System.out.println();
        System.out.println("Usage: java -jar z-cache-core.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -p, --port <port>    Listen port (default: 6379)");
        System.out.println("  -h, --help           Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar z-cache-core.jar");
        System.out.println("  java -jar z-cache-core.jar --port 6380");
    }
}
