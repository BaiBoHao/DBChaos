package chaos.registry;

public class SubsystemDescriptor {
    private final String key;
    private final String title;

    public SubsystemDescriptor(String key, String title) {
        this.key = key;
        this.title = title;
    }

    public String getKey() {
        return key;
    }

    public String getTitle() {
        return title;
    }
}
