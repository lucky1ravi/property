#!/usr/bin/env bash
# Works out which deployable units changed, so the pipelines build and ship only those.
#
#   changes.sh deployed        compare each unit with the commit that is actually deployed (git tag deployed/<unit>)
#   changes.sh ref <git-ref>   compare with where this branch left <git-ref> (used for pull requests)
#   changes.sh all             treat every unit as changed (first deploy, or a forced full deploy)
#
# Writes these lines to $GITHUB_OUTPUT (or stdout when run by hand):
#   backend        JSON array of changed backend services
#   identity       true when identity-service changed (it deploys first: it prepares the shared schema)
#   workers        JSON array of changed services that deploy in the middle
#   gateway        true when api-gateway changed (it deploys last)
#   frontend       JSON array of changed frontend apps (shell and remotes)
#   backend_any    true when anything under backend/ changed
#   frontend_any   true when anything under frontend/ changed
set -euo pipefail

mode="${1:-deployed}"
base_ref="${2:-}"
out="${GITHUB_OUTPUT:-/dev/stdout}"

BACKEND=(identity-service leasing-service billing-service maintenance-service workflow-service ai-service document-service api-gateway)
FRONTEND=(shell leasing payments documents maintenance broker admin insights)
# Paths that affect every backend service / every frontend app.
BACKEND_SHARED=(backend/platform-common backend/pom.xml backend/Dockerfile.service backend/docker-entrypoint.sh)
FRONTEND_SHARED=(frontend/packages frontend/package.json frontend/package-lock.json frontend/tsconfig.base.json)

EMPTY_TREE=$(git hash-object -t tree /dev/null)

# The commit to compare a unit against.
since() {
  case "$mode" in
    all) echo "$EMPTY_TREE" ;;
    ref) git merge-base "$base_ref" HEAD ;;
    deployed)
      if git rev-parse -q --verify "refs/tags/deployed/$1^{commit}" >/dev/null; then git rev-parse "refs/tags/deployed/$1^{commit}"
      else echo "$EMPTY_TREE"; fi ;;
    *) echo "unknown mode: $mode" >&2; exit 2 ;;
  esac
}

# True when any of the paths differ between the unit's reference commit and HEAD.
changed() {
  local unit="$1"; shift
  local from; from=$(since "$unit")
  # A reference we no longer have (history rewritten) counts as changed: deploying again is always safe.
  if [ "$from" != "$EMPTY_TREE" ] && ! git cat-file -e "${from}^{commit}" 2>/dev/null; then return 0; fi
  [ -n "$(git diff --name-only "$from" HEAD -- "$@")" ]
}

json() { jq -cn '$ARGS.positional' --args "$@"; }

backend=(); workers=(); identity=false; gateway=false
for s in "${BACKEND[@]}"; do
  if changed "$s" "backend/$s" "${BACKEND_SHARED[@]}"; then
    backend+=("$s")
    case "$s" in
      identity-service) identity=true ;;
      api-gateway) gateway=true ;;
      *) workers+=("$s") ;;
    esac
  fi
done

frontend=()
for a in "${FRONTEND[@]}"; do
  if changed "$a" "frontend/apps/$a" "${FRONTEND_SHARED[@]}"; then frontend+=("$a"); fi
done

# "Anything changed in backend/ or frontend/" for the pull-request checks.
from_any=$([ "$mode" = ref ] && git merge-base "$base_ref" HEAD || echo "$EMPTY_TREE")
backend_any=false; frontend_any=false
[ -n "$(git diff --name-only "$from_any" HEAD -- backend .github/workflows/ci.yml)" ] && backend_any=true
[ -n "$(git diff --name-only "$from_any" HEAD -- frontend .github/workflows/ci.yml)" ] && frontend_any=true

{
  echo "backend=$(json "${backend[@]}")"
  echo "identity=$identity"
  echo "workers=$(json "${workers[@]}")"
  echo "gateway=$gateway"
  echo "frontend=$(json "${frontend[@]}")"
  echo "backend_any=$backend_any"
  echo "frontend_any=$frontend_any"
} >> "$out"
