package com.zifang.z.cache.core.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main entry point for z-cache server
 */
public class ZCacheServerMain {

    private static final Logger logger = LogManager.getLogger(ZCacheServerMain.class);

    public static void main(String[] args) {
        // 默认只 bind 回环：6379 裸奔在局域网上等于整库可读可写（lead 侧 P1 实测项）。
        // 要对外服务需显式 --host 0.0.0.0 / ZCACHE_HOST，并在容器或部署清单里刻意写出来。
        String host = System.getProperty("zcache.host", "127.0.0.1");
        String dataDir = System.getProperty("zcache.data-dir", System.getenv("ZCACHE_DATA_DIR"));
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
                case "--data-dir":
                    if (i + 1 >= args.length) {
                        fail("--data-dir requires an argument");
                    }
                    dataDir = args[++i];
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
        logger.info("Version: {}", implementationVersion());
        logger.info("Listening on {}:{} (max entries: {})", host, port, maxEntries);
        if (password == null || password.isEmpty()) {
            logger.warn("No AUTH password configured on {}:{} - every RESP client on this address can read and write",
                    host, port);
        }

        RedisServer server = new RedisServer(host, port, maxEntries, password);
        if (dataDir != null && !dataDir.isEmpty()) {
            // 不调用 setDataDir，RDB/AOF 与 BGSAVE 路径全部不生效（1.3.x 之前一直漏在这里）
            server.setDataDir(dataDir);
        }

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
        } catch (Throwable t) {
            // bind 失败（端口被占 / 无权绑定该地址）必须让容器与脚本看得见：
            // 让异常裸抛出去时，Netty 的异常栈会被 shutdown 的噪声淹没，运维只能靠 lsof 猜。
            logger.error("Failed to start z-cache server on {}:{}", host, port, t);
            System.err.println("z-cache: FATAL - cannot listen on " + host + ":" + port + " (" + t + ")");
            System.exit(1);
        }

        logger.info("z-cache server stopped");
    }

    /** 取打包进 MANIFEST 的实现版本；裸 IDE 运行时读不到就如实报 dev，不再硬写一个假版本号。 */
    private static String implementationVersion() {
        String v = ZCacheServerMain.class.getPackage().getImplementationVersion();
        return v == null || v.isEmpty() ? "dev" : v;
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
        System.out.println("  --host <host>        Bind address (default: 127.0.0.1; pass 0.0.0.0 to expose)");
        System.out.println("  --password <value>   Enable AUTH password protection");
        System.out.println("  --password-file <f>  Read AUTH password from file");
        System.out.println("  -p, --port <port>    Listen port (default: 6379)");
        System.out.println("  --max-entries <n>    Maximum keys, 0 means unlimited");
        System.out.println("  --data-dir <dir>     Enable RDB/AOF persistence and snapshots in <dir>");
        System.out.println("  -h, --help           Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar z-cache-core.jar");
        System.out.println("  java -jar z-cache-core.jar --port 6380");
    }
}
