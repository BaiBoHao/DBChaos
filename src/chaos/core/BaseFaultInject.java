package chaos.core;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 基础故障注入类。
 */
public abstract class BaseFaultInject {
    protected static final Logger LOG = Logger.getLogger(BaseFaultInject.class.getName());
    
    protected String url;
    protected String user;
    protected String password;
    protected String faultType;
    protected String dbType;
    protected String SQLType;

    // 可覆盖配置
    public static String overrideUrl = null;
    public static String overrideUser = null;
    public static String overridePassword = null;

    
    private String standardizeDbType(String dbType) {
        if (dbType == null) return "unknown";
        
        String lowerType = dbType.toLowerCase();
        switch (lowerType) {
            case "ob":
            case "oceanbase":
            case "mysql":
                return "mysql";
            case "opengauss":
            case "postgresql":
            case "og":
                return "postgresql";
            default:
                return lowerType;
        }
    }

    private void loadConfig() {
        try (InputStream in = BaseFaultInject.class.getResourceAsStream("/db.properties")) {
            if (in == null) {
                throw new RuntimeException("在 classpath 中未找到 /db.properties 文件");
            }
            
            Properties props = new Properties();
            props.load(in);

            // 优先使用覆盖配置；未覆盖时优先读取数据库类型专属配置，再回退到通用配置。
            this.url = chooseConfigValue(props, "url", overrideUrl);
            this.user = chooseConfigValue(props, "user", overrideUser);
            this.password = chooseConfigValue(props, "password", overridePassword);

            if (this.url == null || this.user == null || this.password == null) {
                throw new RuntimeException("db.properties 配置不完整");
            }

            // 适用java1.8及以上版本，自动加载驱动
            String driverClass;
            String lowerDb = dbType.toLowerCase();
            switch (lowerDb) {
                case "opengauss":
                    // Some openGauss JDBC distributions expose the driver as org.postgresql.Driver.
                    driverClass = "org.opengauss.Driver";
                    break;
                case "postgresql":
                    driverClass = "org.postgresql.Driver";
                    break;
                case "mysql":
                case "oceanbase":
                    driverClass = "com.mysql.cj.jdbc.Driver";
                    break;
                default:
                    throw new RuntimeException("不支持的数据库驱动类型: " + dbType);
            }
            
            try {
                Class.forName(driverClass);
            } catch (ClassNotFoundException e) {
                // openGauss JDBC is often packaged under PostgreSQL driver namespace.
                if ("opengauss".equals(lowerDb)) {
                    try {
                        Class.forName("org.postgresql.Driver");
                    } catch (ClassNotFoundException e2) {
                        throw new RuntimeException("找不到驱动类 org.opengauss.Driver 或 org.postgresql.Driver，请检查 JDBC 依赖是否已正确打包。");
                    }
                } else {
                    throw new RuntimeException("找不到驱动类 " + driverClass + "，请检查 JDBC 依赖是否已正确打包。");
                }
            }
            
        } catch (Exception e) {
            throw new RuntimeException("初始化基类失败: " + e.getMessage());
        }
    }

    private String chooseConfigValue(Properties props, String keySuffix, String overrideVal) {
        if (overrideVal != null && !overrideVal.trim().isEmpty()) {
            return overrideVal;
        }

        String[] candidates = getDbConfigPrefixes();
        for (String prefix : candidates) {
            String v = props.getProperty(prefix + "." + keySuffix);
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }

        String fallback = props.getProperty(keySuffix);
        return (fallback == null || fallback.trim().isEmpty()) ? null : fallback;
    }

    private String[] getDbConfigPrefixes() {
        String lower = (dbType == null) ? "" : dbType.toLowerCase();
        switch (lower) {
            case "mysql":
                return new String[] {"mysql"};
            case "oceanbase":
            case "ob":
                return new String[] {"oceanbase", "ob", "mysql"};
            case "opengauss":
            case "og":
                return new String[] {"opengauss", "og", "postgresql"};
            case "postgresql":
                return new String[] {"postgresql"};
            default:
                return new String[0];
        }
    }

    protected Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    protected String getStandardDbType() {
        return this.SQLType;
    }

    protected boolean hasArg(String[] args, String target) {
        for (String arg : args) {
            if (target.equalsIgnoreCase(arg)) return true;
        }
        return false;
    }

    protected String getArg(String[] args, String target) {
        for (int i = 0; i < args.length - 1; i++) {
            if (target.equalsIgnoreCase(args[i])) return args[i + 1];
        }
        return null;
    }

    public void printHelp() {}

    public BaseFaultInject(String dbType, String faultType) {
        this.dbType = dbType;
        this.faultType = faultType;
        this.SQLType = standardizeDbType(dbType);
        this.loadConfig();
    }

    public abstract void execute(String[] args) throws Exception;
}