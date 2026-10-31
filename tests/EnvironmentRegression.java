import chaos.core.PreflightEnvironment;

/** Runs only against the explicitly supplied test checker executable. */
public class EnvironmentRegression {
    public static void main(String[] args) throws Exception {
        try {
            if (args[0].equals("storage")) {
                PreflightEnvironment.storage("jdbc:test:preflight", "test", "TEST", 30000, 100);
            } else if (args[0].equals("business")) {
                PreflightEnvironment.businessHotspots("jdbc:test:preflight", "test", "TEST", 30000, "public.test", 1000);
            } else {
                PreflightEnvironment.clientMemory("jdbc:test:preflight", "test", "TEST", 30000, 4);
            }
            System.out.println("ENVIRONMENT_PASS");
        } catch (Exception e) {
            System.out.println("ENVIRONMENT_FAIL: " + e.getMessage());
            System.exit(1);
        }
    }
}
