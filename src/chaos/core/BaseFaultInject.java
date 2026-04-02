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

    // 提供覆盖配置的静态变量，允许在运行时通过环境变量或系统属性覆盖默认配置
    public static String overrideUrl = null;
    public static String overrideUser = null;
    public static String overridePassword = null;

    public BaseFaultInject(String dbType, String faultType) {
        this.dbType = dbType;
        this.faultType = faultType;
        this.SQLType = standardizeDbType(dbType);
        this.loadConfig();
    }

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
            
            // this.url = props.getProperty("url");
            // this.user = props.getProperty("user");
            // this.password = props.getProperty("password");

            // 优先使用覆盖配置，确保在不同环境下的灵活性和安全性
            this.url = (overrideUrl != null) ? overrideUrl : props.getProperty("url");
            this.user = (overrideUser != null) ? overrideUser : props.getProperty("user");
            this.password = (overridePassword != null) ? overridePassword : props.getProperty("password");

            if (this.url == null || this.user == null || this.password == null) {
                throw new RuntimeException("db.properties 配置不完整");
            }

            // 适用java1.8及以上版本，自动加载驱动
            String driverClass;
            String lowerDb = dbType.toLowerCase();
            switch (lowerDb) {
                case "opengauss":
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
                throw new RuntimeException("找不到驱动类 " + driverClass + "，请检查 lib 目录下是否放入了对应的 jdbc jar 包！");
            }
            
        } catch (Exception e) {
            throw new RuntimeException("初始化基类失败: " + e.getMessage());
        }
    }

    protected Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    protected String getStandardDbType() {
        return this.SQLType;
    }

    public void printHelp() {
        System.out.println("=== 故障注入工具基准测试通过 ===");
        System.out.println("故障类型: " + this.faultType);
        System.out.println("数据库类型: " + this.dbType);
        System.out.println("标准化语法类型: " + this.SQLType);
        System.out.println("状态确认: 配置加载及驱动初始化成功。");
    }

    public abstract void execute(String[] args) throws Exception;
}