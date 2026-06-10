package chaos.registry;

import chaos.core.BaseFaultInject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CaseRegistry {
    private static final String SUBSYSTEM_RESOURCE = "/registry/subsystems.tsv";
    private static final String CASE_RESOURCE = "/registry/cases.tsv";
    private static final CaseRegistry INSTANCE = loadDefault();

    private final List<SubsystemDescriptor> subsystems;
    private final Map<String, SubsystemDescriptor> subsystemByKey;
    private final List<CaseDescriptor> cases;

    private CaseRegistry(
            List<SubsystemDescriptor> subsystems,
            Map<String, SubsystemDescriptor> subsystemByKey,
            List<CaseDescriptor> cases
    ) {
        this.subsystems = Collections.unmodifiableList(new ArrayList<SubsystemDescriptor>(subsystems));
        this.subsystemByKey = Collections.unmodifiableMap(new LinkedHashMap<String, SubsystemDescriptor>(subsystemByKey));
        this.cases = Collections.unmodifiableList(new ArrayList<CaseDescriptor>(cases));
    }

    public static CaseRegistry getInstance() {
        return INSTANCE;
    }

    public List<SubsystemDescriptor> getSubsystems() {
        return subsystems;
    }

    public List<CaseDescriptor> getCasesForSubsystem(String subsystem) {
        List<CaseDescriptor> result = new ArrayList<CaseDescriptor>();
        for (CaseDescriptor descriptor : cases) {
            if (descriptor.getSubsystem().equalsIgnoreCase(subsystem)) {
                result.add(descriptor);
            }
        }
        return result;
    }

    public boolean isKnownSubsystem(String subsystem) {
        return subsystemByKey.containsKey(subsystem.toLowerCase());
    }

    public boolean isKnownCaseKeyword(String caseKey) {
        for (CaseDescriptor descriptor : cases) {
            if (descriptor.matchesCaseKey(caseKey)) {
                return true;
            }
        }
        return false;
    }

    public String getSubsystemTitle(String subsystem) {
        SubsystemDescriptor descriptor = subsystemByKey.get(subsystem.toLowerCase());
        return descriptor == null ? subsystem : descriptor.getTitle();
    }

    public CaseDescriptor findCaseDescriptor(String subsystem, String caseKey) {
        for (CaseDescriptor descriptor : cases) {
            if (descriptor.getSubsystem().equalsIgnoreCase(subsystem) && descriptor.matchesCaseKey(caseKey)) {
                return descriptor;
            }
        }
        return null;
    }

    public List<String> findSubsystemsForCase(String caseKey) {
        List<String> owners = new ArrayList<String>();
        for (CaseDescriptor descriptor : cases) {
            if (descriptor.matchesCaseKey(caseKey) && !owners.contains(descriptor.getSubsystem())) {
                owners.add(descriptor.getSubsystem());
            }
        }
        return owners;
    }

    public BaseFaultInject createInjector(String dbType, String subsystem, String caseKey) {
        CaseDescriptor descriptor = findCaseDescriptor(subsystem, caseKey);
        if (descriptor == null) {
            return null;
        }

        try {
            Class<?> clazz = Class.forName(descriptor.getInjectorClass());
            Constructor<?> constructor = clazz.getConstructor(String.class);
            return (BaseFaultInject) constructor.newInstance(dbType);
        } catch (Exception e) {
            throw new RuntimeException("无法创建注入器 " + descriptor.getInjectorClass() + ": " + e.getMessage(), e);
        }
    }

    public List<CaseDescriptor> getExampleCases(int limit) {
        List<CaseDescriptor> result = new ArrayList<CaseDescriptor>();
        List<String> seenSubsystems = new ArrayList<String>();
        for (CaseDescriptor descriptor : cases) {
            if (descriptor.getExampleArgs() == null || descriptor.getExampleArgs().trim().isEmpty()) {
                continue;
            }
            if (!seenSubsystems.contains(descriptor.getSubsystem())) {
                result.add(descriptor);
                seenSubsystems.add(descriptor.getSubsystem());
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private static CaseRegistry loadDefault() {
        List<SubsystemDescriptor> subsystems = loadSubsystems();
        Map<String, SubsystemDescriptor> subsystemByKey = new LinkedHashMap<String, SubsystemDescriptor>();
        for (SubsystemDescriptor subsystem : subsystems) {
            subsystemByKey.put(subsystem.getKey().toLowerCase(), subsystem);
        }
        List<CaseDescriptor> cases = loadCases();
        return new CaseRegistry(subsystems, subsystemByKey, cases);
    }

    private static List<SubsystemDescriptor> loadSubsystems() {
        List<SubsystemDescriptor> result = new ArrayList<SubsystemDescriptor>();
        try (BufferedReader reader = openResource(SUBSYSTEM_RESOURCE)) {
            String line;
            boolean headerSkipped = false;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }
                String[] parts = line.split("\t", -1);
                result.add(new SubsystemDescriptor(parts[0].trim(), parts[1].trim()));
            }
        } catch (Exception e) {
            throw new RuntimeException("加载子系统注册表失败: " + e.getMessage(), e);
        }
        return result;
    }

    private static List<CaseDescriptor> loadCases() {
        List<CaseDescriptor> result = new ArrayList<CaseDescriptor>();
        try (BufferedReader reader = openResource(CASE_RESOURCE)) {
            String line;
            boolean headerSkipped = false;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }
                String[] parts = line.split("\t", -1);
                result.add(new CaseDescriptor(
                        parts[0].trim(),
                        parts[1].trim(),
                        parts[2].trim(),
                        parts[3].trim(),
                        parts[4].trim(),
                        parts[5].trim(),
                        parts[6].trim(),
                        splitPipe(parts[7]),
                        splitPipe(parts[8])
                ));
            }
        } catch (Exception e) {
            throw new RuntimeException("加载 Case 注册表失败: " + e.getMessage(), e);
        }
        return result;
    }

    private static BufferedReader openResource(String resourcePath) throws Exception {
        InputStream in = CaseRegistry.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new RuntimeException("找不到注册资源: " + resourcePath);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static List<String> splitPipe(String raw) {
        List<String> result = new ArrayList<String>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        for (String item : raw.split("\\|")) {
            String cleaned = item.trim();
            if (!cleaned.isEmpty()) {
                result.add(cleaned);
            }
        }
        return result;
    }
}
