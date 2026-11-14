# On the unseal Vault: what the prod Vault servers may do with their transit key. Nothing else:
# no reading of the key, no rotation, no other key. __ENV__ is replaced by setup-transit.sh.
path "transit/encrypt/ebank-__ENV__-unseal" { capabilities = ["update"] }
path "transit/decrypt/ebank-__ENV__-unseal" { capabilities = ["update"] }
