package entralinked.model.user;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class User {
    
    private final String id;
    private final String password; // I debated hashing it, but.. it's a 3-digit password...
    private final Map<String, GameProfile> profiles = new HashMap<>();
    
    public User(String id, String password) {
        this.id = id;
        this.password = password;
    }
    
    public String getFormattedId() {
        return "%s000".formatted(id).replaceAll("(.{4})(?!$)", "$1-");
    }
    
    public String getId() {
        return id;
    }
    
    public String getPassword() {
        return password;
    }
    
    protected void addProfile(String branchCode, GameProfile profile) {
        profiles.put(branchCode, profile);
    }
    
    protected void removeProfile(String branchCode) {
        profiles.remove(branchCode);
    }
    
    public GameProfile getProfile(String branchCode) {
        return profiles.get(branchCode);
    }
    
    public Collection<GameProfile> getProfiles() {
        return Collections.unmodifiableCollection(profiles.values());
    }
    
    protected Map<String, GameProfile> getProfileMap() {
        return Collections.unmodifiableMap(profiles);
    }
}
