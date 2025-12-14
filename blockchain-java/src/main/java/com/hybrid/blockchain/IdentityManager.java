package com.hybrid.blockchain;

import java.security.*;
import java.util.*;

public class IdentityManager {
    private final Map<String, PublicKey> authorizedNodes;

    public IdentityManager() {
        authorizedNodes = new HashMap<>();
    }

    public void registerNode(String nodeId, PublicKey pubKey) {
        authorizedNodes.put(nodeId, pubKey);
    }

    public boolean isAuthorized(String nodeId, PublicKey pubKey) {
        PublicKey storedKey = authorizedNodes.get(nodeId);
        return storedKey != null && storedKey.equals(pubKey);
    }

    public Set<String> getAuthorizedNodes() {
        return authorizedNodes.keySet();
    }
}
