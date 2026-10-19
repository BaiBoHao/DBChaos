import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Rate-controlled real JDBC UPDATE workload, emitting the BenchmarkSQL CSV schema (not TPC-C). */
public class BusinessFeed {
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        try(InputStream input = new FileInputStream(args[0])) { p.load(input); }
        Class.forName("org.opengauss.Driver");
        Path out = Paths.get(args[1]); Files.createDirectories(out);
        try(Connection c = DriverManager.getConnection(p.getProperty("url"), p.getProperty("user"), p.getProperty("password"));
            Statement init = c.createStatement();
            PreparedStatement update = c.prepareStatement("UPDATE public.business_hotspot SET val=val+1 WHERE id=1");
            BufferedWriter writer = Files.newBufferedWriter(out.resolve("result.csv"), StandardCharsets.UTF_8)) {
            init.execute("SET application_name = 'resilience_business'");
            init.execute("SET statement_timeout = '700ms'");
            update.setQueryTimeout(2);
            long epoch = System.currentTimeMillis();
            Files.write(out.resolve("runInfo.csv"), ("run,sessionStart\n1," + new Timestamp(epoch) + "\n").getBytes(StandardCharsets.UTF_8));
            writer.write("run,elapsed,latency,dblatency,ttype,rbk,dskipped,error\n"); writer.flush();
            Files.write(out.resolve("ready"), new byte[0]);
            long next = System.nanoTime();
            while(!Files.exists(out.resolve("stop"))) {
                long start=System.nanoTime(); String error="0";
                try { update.executeUpdate(); }
                catch(SQLException e) { error=String.valueOf(e.getSQLState()); }
                long latency=Math.max(1,(System.nanoTime()-start+999999)/1000000);
                writer.write("1,"+(System.currentTimeMillis()-epoch)+","+latency+","+latency+",UPDATE,0,0,"+error+"\n");
                writer.flush();
                next=Math.max(next+10000000,System.nanoTime());
                long wait=next-System.nanoTime();
                if(wait>0) Thread.sleep(wait/1000000,(int)(wait%1000000));
            }
        }
    }
}
