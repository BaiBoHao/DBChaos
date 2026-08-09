package chaos.registry;

import chaos.core.BaseFaultInject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CaseRegistry {
    private static final String REGISTRY_RESOURCE = "/registry/registry.json";
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
        Map<String, Object> root = loadRegistryRoot();
        List<SubsystemDescriptor> subsystems = loadSubsystems(root);
        Map<String, SubsystemDescriptor> subsystemByKey = new LinkedHashMap<String, SubsystemDescriptor>();
        for (SubsystemDescriptor subsystem : subsystems) {
            subsystemByKey.put(subsystem.getKey().toLowerCase(), subsystem);
        }
        List<CaseDescriptor> cases = loadCases(root);
        return new CaseRegistry(subsystems, subsystemByKey, cases);
    }

    private static Map<String, Object> loadRegistryRoot() {
        try {
            Object parsed = SimpleJsonParser.parse(readResource(REGISTRY_RESOURCE));
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("根节点必须是 JSON 对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) parsed;
            return root;
        } catch (Exception e) {
            throw new RuntimeException("加载注册表失败: " + e.getMessage(), e);
        }
    }

    private static List<SubsystemDescriptor> loadSubsystems(Map<String, Object> root) {
        List<SubsystemDescriptor> result = new ArrayList<SubsystemDescriptor>();
        for (Map<String, Object> item : readObjectList(root.get("subsystems"), "subsystems")) {
            result.add(new SubsystemDescriptor(
                    readRequiredString(item, "key"),
                    readRequiredString(item, "title")
            ));
        }
        return result;
    }

    private static List<CaseDescriptor> loadCases(Map<String, Object> root) {
        List<CaseDescriptor> result = new ArrayList<CaseDescriptor>();
        for (Map<String, Object> item : readObjectList(root.get("cases"), "cases")) {
            result.add(new CaseDescriptor(
                    readRequiredString(item, "subsystem"),
                    readRequiredString(item, "caseKey"),
                    readRequiredString(item, "title"),
                    readRequiredString(item, "description"),
                    readRequiredString(item, "injectorClass"),
                    readOptionalString(item, "exampleArgs"),
                    readOptionalString(item, "defaultMode"),
                    readStringList(item.get("allowedModes")),
                    readStringList(item.get("aliases"))
            ));
        }
        return result;
    }

    private static String readResource(String resourcePath) throws Exception {
        InputStream in = CaseRegistry.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalArgumentException("找不到注册资源: " + resourcePath);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringWriter writer = new StringWriter();
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, read);
            }
            return writer.toString();
        }
    }

    private static List<Map<String, Object>> readObjectList(Object raw, String fieldName) {
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException(fieldName + " 必须是数组");
        }

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        List<?> list = (List<?>) raw;
        for (Object item : list) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException(fieldName + " 中存在非对象元素");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) item;
            result.add(object);
        }
        return result;
    }

    private static String readRequiredString(Map<String, Object> object, String fieldName) {
        String value = readOptionalString(object, fieldName);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("字段 " + fieldName + " 不能为空");
        }
        return value;
    }

    private static String readOptionalString(Map<String, Object> object, String fieldName) {
        Object raw = object.get(fieldName);
        if (raw == null) {
            return "";
        }
        if (!(raw instanceof String)) {
            throw new IllegalArgumentException("字段 " + fieldName + " 必须是字符串");
        }
        return ((String) raw).trim();
    }

    private static List<String> readStringList(Object raw) {
        List<String> result = new ArrayList<String>();
        if (raw == null) {
            return result;
        }
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException("数组字段格式非法");
        }
        for (Object item : (List<?>) raw) {
            if (!(item instanceof String)) {
                throw new IllegalArgumentException("数组字段中存在非字符串元素");
            }
            String value = ((String) item).trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }
}
