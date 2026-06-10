package chaos.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CaseDescriptor {
    private final String subsystem;
    private final String caseKey;
    private final String title;
    private final String description;
    private final String injectorClass;
    private final String exampleArgs;
    private final String defaultMode;
    private final List<String> allowedModes;
    private final List<String> aliases;

    public CaseDescriptor(
            String subsystem,
            String caseKey,
            String title,
            String description,
            String injectorClass,
            String exampleArgs,
            String defaultMode,
            List<String> allowedModes,
            List<String> aliases
    ) {
        this.subsystem = subsystem;
        this.caseKey = caseKey;
        this.title = title;
        this.description = description;
        this.injectorClass = injectorClass;
        this.exampleArgs = exampleArgs;
        this.defaultMode = defaultMode;
        this.allowedModes = Collections.unmodifiableList(new ArrayList<String>(allowedModes));
        this.aliases = Collections.unmodifiableList(new ArrayList<String>(aliases));
    }

    public String getSubsystem() {
        return subsystem;
    }

    public String getCaseKey() {
        return caseKey;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getInjectorClass() {
        return injectorClass;
    }

    public String getExampleArgs() {
        return exampleArgs;
    }

    public String getDefaultMode() {
        return defaultMode;
    }

    public List<String> getAllowedModes() {
        return allowedModes;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public boolean hasModeConstraint() {
        return !allowedModes.isEmpty();
    }

    public boolean hasDefaultMode() {
        return defaultMode != null && !defaultMode.trim().isEmpty();
    }

    public boolean matchesCaseKey(String value) {
        if (caseKey.equalsIgnoreCase(value)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
