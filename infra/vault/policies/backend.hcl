# The backend of one environment reads its own config and secrets, nothing else.
# __ENV__ is replaced by apply.sh.
path "ebank/data/__ENV__/backend/*" {
  capabilities = ["read"]
}
