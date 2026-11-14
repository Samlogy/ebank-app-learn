# VaultRaftUnhealthy, VaultNoActiveNode

**Meaning.** prod runs three Vault servers in one Raft cluster (`vault-values-ha.yaml`). `VaultRaftUnhealthy` (warning): autopilot reports an unhealthy server or no failure tolerance left, so losing one more server loses the quorum. `VaultNoActiveNode` (critical): servers are unsealed but none is the leader, so every request fails.

**Impact.** Warning: none yet. Critical: as [vault-unavailable.md](vault-unavailable.md).

**Check.**
1. `kubectl -n vault get pods -o wide`: all three running, on three different nodes?
2. `kubectl -n vault exec vault-0 -- sh -c 'VAULT_TOKEN=<admin token> vault operator raft list-peers'`: three voters, one leader.
3. `vault operator raft autopilot state` (same way): which server is unhealthy and why (last contact, last index).
4. A pod Pending: its node is gone or full, or the anti-affinity has no third node to use.

**Fix.**
- A server that does not rejoin: delete its pod; it rejoins through `retry_join` and unseals with the transit key.
- A server whose volume is lost: remove it from the peers (`vault operator raft remove-peer vault-<n>`), delete its PVC and pod; it joins again as a new server and copies the data.
- No leader with two servers up: check the network between pods (port 8201).

**Escalate.** Two servers lost at the same time: restore from a snapshot (`vault operator raft snapshot restore`), with whoever holds the backups and the recovery keys.
