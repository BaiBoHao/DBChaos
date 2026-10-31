"""Instrument an isolated BenchmarkSQL copy for reliable fault experiments."""
from pathlib import Path

def instrument(source):
 source=Path(source)
 p=source/'client/jTPCC.java';text=p.read_text()
 text=text.replace('public void resultAppend(jTPCCTData term)','public synchronized void resultAppend(jTPCCTData term)')
 text=text.replace('term.resultLine(sessionStartTimestamp));','term.resultLine(sessionStartTimestamp));\n                resultCSV.flush();')
 old='copyFile(new File(System.getProperty("prop")),\n\t\t\t new File(resultDir, "run.properties"));'
 assert old in text
 text=text.replace(old,'Properties safeProps = new Properties(); safeProps.putAll(ini);\n                safeProps.setProperty("password", "[REDACTED]");\n                try (FileOutputStream safeOutput = new FileOutputStream(new File(resultDir, "run.properties"))) { safeProps.store(safeOutput, "Redacted benchmark configuration"); }')
 p.write_text(text)
 p=source/'client/jTPCCTData.java';text=p.read_text()
 # The installed fork logs SQLExceptions but never assigns transError. Ensure its
 # result.csv error field actually excludes failed SQL transactions from successful TPS.
 text=text.replace('catch (SQLException se)\n\t{','catch (SQLException se)\n\t{\n            transError = se.toString();')
 text=text.replace('    public String resultLine(long sessionStart)','    public void markFailed(Exception error) {\n        transError = error.toString();\n        transEnd = System.currentTimeMillis();\n        if (transStart == 0) transStart = transEnd;\n        if (transDue == 0) transDue = transStart;\n    }\n\n    public String resultLine(long sessionStart)')
 p.write_text(text)
 p=source/'client/jTPCCTerminal.java';text=p.read_text()
 text=text.replace('jTPCCTData      term = new jTPCCTData();','boolean resultRecorded = false;\n                jTPCCTData      term = new jTPCCTData();')
 text=text.replace('parent.resultAppend(term);','parent.resultAppend(term);\n                    resultRecorded = true;')
 old='\t\t    log.fatal(e.getMessage());\n\t\t    e.printStackTrace();\n\t\t    System.exit(4);'
 assert text.count(old)==5
 text=text.replace(old,'                    log.error("BUSINESS_TRANSACTION_FAILED " + e.toString());\n                    if (!resultRecorded) { term.markFailed(e); parent.resultAppend(term); }\n                    recoverConnection();')
 method='''
    private void recoverConnection() {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) { }
        while (!stopRunningSignal) {
            try {
                Properties settings = new Properties();
                try (InputStream input = new FileInputStream(System.getProperty("prop"))) { settings.load(input); }
                conn = DriverManager.getConnection(settings.getProperty("conn"), settings.getProperty("user"), settings.getProperty("password"));
                conn.setAutoCommit(false);
                db = new jTPCCConnection(conn, dbType);
                stmt = conn.createStatement(); stmt.setMaxRows(200); stmt.setFetchSize(100);
                stmt1 = conn.createStatement(); stmt1.setMaxRows(1);
                log.warn("BUSINESS_RECONNECTED terminal=" + terminalName + " epoch_ms=" + System.currentTimeMillis());
                return;
            } catch (Exception failure) {
                log.warn("BUSINESS_RECONNECT_FAILED terminal=" + terminalName + " epoch_ms=" + System.currentTimeMillis());
                try { Thread.sleep(1000); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
'''
 text=text.replace('this.db = new jTPCCConnection(conn, dbType);', 'this.db = new jTPCCConnection(conn, dbType);\n        if (Boolean.getBoolean("dbchaos.validate.disconnect")) {\n            final Connection validationConnection = conn;\n            new Thread(() -> {\n                try { Thread.sleep(5000); validationConnection.close(); log.warn("VALIDATION_CLIENT_SOCKET_CLOSED terminal=" + terminalName); }\n                catch (Exception failure) { log.error("VALIDATION_SOCKET_CLOSE_FAILED", failure); }\n            }, "validation-disconnect-" + terminalName).start();\n        }')
 text=text.replace('    private void error(String type) {',method+'\n    private void error(String type) {')
 p.write_text(text)
