package chaos.tools;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * 数据库连接探测工具。
 * 用于在执行不利注入前验证 resources/db.properties 或命令行覆盖参数是否可成功建立连接。
 */
public class DbConnectionProbe {
    private static final String RESET  = "\u001B[0m";
    private static final String CYAN   = "\u001B[36m";
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String BOLD   = "\u001B[1m";

    public static void main(String[] args) {
        try {
            ConnectionConfig config = resolveConfig(args);
            printHeader(config);

            String driverClass = loadDriver(config.dbType);
            try (Connection conn = DriverManager.getConnection(config.url, config.user, config.password);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                if (rs.next()) {
                    rs.getInt(1);
                }
            }

            System.out.println(GREEN + BOLD + " 状态: SUCCESS" + RESET);
            System.out.println(GREEN + " 数据库连接成功，可以继续进行不利注入。" + RESET);
            System.out.println(CYAN + " Driver: " + driverClass + RESET);
        } catch (Exception e) {
            System.out.println(RED + BOLD + " 状态: FAILED" + RESET);
            System.out.println(RED + " 数据库连接失败，建议先修正配置再执行不利注入。" + RESET);
            System.out.println(YELLOW + " 原因: " + e.getMessage() + RESET);
            System.exit(1);
        }
    }

    private static ConnectionConfig resolveConfig(String[] args) throws Exception {
        Properties props = new Properties();
        try (InputStream in = DbConnectionProbe.class.getResourceAsStream("/db.properties")) {
            if (in == null) {
                throw new RuntimeException("未在 classpath 中找到 /db.properties");
            }
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        String dbType = findArg(args, "--db");
        if (dbType == null) {
            dbType = props.getProperty("type", "opengauss");
        }
        dbType = dbType.trim().toLowerCase();

        String overrideUrl = findArg(args, "-url");
        String overrideUser = findArg(args, "-user");
        String overridePassword = findArg(args, "-password");

        String url = chooseConfigValue(props, dbType, "url", overrideUrl);
        String user = chooseConfigValue(props, dbType, "user", overrideUser);
        String password = chooseConfigValue(props, dbType, "password", overridePassword);

        if (url == null || user == null || password == null) {
            throw new RuntimeException("db.properties 缺少连接配置，至少需要 type/url/user/password");
        }

        return new ConnectionConfig(dbType, url, user, password);
    }

    private static String chooseConfigValue(Properties props, String dbType, String keySuffix, String overrideValue) {
        if (overrideValue != null && !overrideValue.trim().isEmpty()) {
            return overrideValue.trim();
        }

        String[] candidates = getDbConfigPrefixes(dbType);
        for (String prefix : candidates) {
            String value = props.getProperty(prefix + "." + keySuffix);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }

        String fallback = props.getProperty(keySuffix);
        return (fallback == null || fallback.trim().isEmpty()) ? null : fallback.trim();
    }

    private static String[] getDbConfigPrefixes(String dbType) {
        switch (dbType.toLowerCase()) {
            case "mysql":
                return new String[] {"mysql"};
            case "oceanbase":
            case "ob":
                return new String[] {"oceanbase", "ob", "mysql"};
            case "opengauss":
            case "og":
                return new String[] {"opengauss", "og", "postgresql"};
            case "postgresql":
            case "pg":
                return new String[] {"postgresql", "pg"};
            default:
                return new String[0];
        }
    }

    private static String loadDriver(String dbType) throws Exception {
        String lowerDb = dbType.toLowerCase();
        String driverClass;
        switch (lowerDb) {
            case "opengauss":
            case "og":
                driverClass = "org.opengauss.Driver";
                break;
            case "postgresql":
            case "pg":
                driverClass = "org.postgresql.Driver";
                break;
            case "mysql":
            case "oceanbase":
            case "ob":
                driverClass = "com.mysql.cj.jdbc.Driver";
                break;
            default:
                throw new RuntimeException("不支持的数据库类型: " + dbType);
        }

        try {
            Class.forName(driverClass);
            return driverClass;
        } catch (ClassNotFoundException e) {
            if ("opengauss".equals(lowerDb) || "og".equals(lowerDb)) {
                Class.forName("org.postgresql.Driver");
                return "org.postgresql.Driver";
            }
            throw new RuntimeException("找不到驱动类 " + driverClass + "，请检查 lib/ 目录中的 JDBC 依赖");
        }
    }

    private static void printHeader(ConnectionConfig config) {
        System.out.println(CYAN + BOLD + "DBChaos Database Connection Probe" + RESET);
        System.out.println("  数据库类型 : " + config.dbType);
        System.out.println("  连接地址   : " + config.url);
        System.out.println("  用户名     : " + config.user);
        System.out.println(CYAN + "  开始探测数据库连接..." + RESET);
    }

    private static String findArg(String[] args, String key) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equalsIgnoreCase(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static final class ConnectionConfig {
        private final String dbType;
        private final String url;
        private final String user;
        private final String password;

        private ConnectionConfig(String dbType, String url, String user, String password) {
            this.dbType = dbType;
            this.url = url;
            this.user = user;
            this.password = password;
        }
    }
}
