import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Two low-rate availability probes; this is not a throughput benchmark. */
public class SessionProbe {
    static Properties config = new Properties();
    static Path folder;
    static Connection connect() throws SQLException {
        return DriverManager.getConnection(config.getProperty("url"), config.getProperty("user"), config.getProperty("password"));
    }
    static void query(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) { s.setQueryTimeout(3); s.execute("SELECT 1"); }
    }
    static void sample(boolean fresh) {
        String kind = fresh ? "fresh" : "persistent";
        Connection persistent = null;
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(folder.resolve(kind + ".csv")))) {
            out.println("epoch_ms,latency_ms,success,sqlstate"); out.flush();
            if (!fresh) { persistent = connect(); query(persistent); }
            Files.write(folder.resolve(kind + ".ready"), new byte[0]);
            while (!Files.exists(folder.resolve("stop"))) {
                long epoch = System.currentTimeMillis(), start = System.nanoTime();
                boolean ok = true; String state = "";
                try {
                    if (fresh) { try (Connection c = connect()) { query(c); } }
                    else { if (persistent == null || persistent.isClosed()) persistent = connect(); query(persistent); }
                } catch (SQLException e) { ok = false; state = String.valueOf(e.getSQLState()); }
                double elapsed = (System.nanoTime() - start) / 1e6;
                out.printf(Locale.ROOT, "%d,%.3f,%s,%s%n", epoch, elapsed, ok, state); out.flush();
                long pause = (fresh ? 1000 : 200) - (long)elapsed;
                if (pause > 0) Thread.sleep(pause);
            }
        } catch (Exception e) { e.printStackTrace(); System.exit(1); }
        finally { if (persistent != null) try { persistent.close(); } catch (SQLException ignored) {} }
    }
    public static void main(String[] args) throws Exception {
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) { config.load(in); }
        folder = Paths.get(args[1]); Files.createDirectories(folder);
        Class.forName("org.opengauss.Driver"); DriverManager.setLoginTimeout(4);
        Thread a = new Thread(() -> sample(false)), b = new Thread(() -> sample(true));
        a.start(); b.start(); a.join(); b.join();
    }
}
