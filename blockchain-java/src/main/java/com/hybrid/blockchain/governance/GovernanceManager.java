package com.hybrid.blockchain.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the HybridChain Governance Council and proposal voting.
 * 
 * <p>Council size: 7 members</p>
 * <p>Quorum threshold: 5 members (71%)</p>
 */
public class GovernanceManager {
    private static final Logger log = LoggerFactory.getLogger(GovernanceManager.class);

    public static final int COUNCIL_SIZE = 7;
    public static final int PROPOSE_THRESHOLD = 5;

    private final Set<String> councilMembers = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> activeProposals = new ConcurrentHashMap<>(); // proposalId -> voters

    public GovernanceManager(List<String> initialMembers) {
        if (initialMembers != null) {
            initialMembers.stream().limit(COUNCIL_SIZE).forEach(councilMembers::add);
        }
        log.info("[GOVERNANCE] Initialized with {} council members", councilMembers.size());
    }

    public boolean isCouncilMember(String address) {
        return councilMembers.contains(address);
    }

    /**
     * Submit a vote for a proposal.
     * 
     * @return true if the proposal reached quorum.
     */
    public boolean vote(String voterAddress, String proposalId) {
        if (!isCouncilMember(voterAddress)) {
            log.warn("[GOVERNANCE] Rejected vote from non-council member: {}", voterAddress);
            return false;
        }

        Set<String> voters = activeProposals.computeIfAbsent(proposalId, k -> ConcurrentHashMap.newKeySet());
        voters.add(voterAddress);

        log.info("[GOVERNANCE] Vote for {} from {}. Total votes: {}/{}", 
                proposalId, voterAddress, voters.size(), PROPOSE_THRESHOLD);

        if (voters.size() >= PROPOSE_THRESHOLD) {
            log.info("[GOVERNANCE] Proposal {} REACHED QUORUM!", proposalId);
            return true;
        }
        return false;
    }

    public void clearProposal(String proposalId) {
        activeProposals.remove(proposalId);
    }

    public Set<String> getCouncilMembers() {
        return Collections.unmodifiableSet(councilMembers);
    }
}
