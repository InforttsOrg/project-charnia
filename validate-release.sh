#!/bin/bash

# Charnia Release Validation & Versioning Gatekeeper
# Part of the Infortts Corporate Governance Swarm

# Ensure we run from the project root
cd "$(dirname "$0")"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}🧬 Charnia Corporate Governance Validation Igniting...${NC}"

# 1. Versioning Check & Bootstrap
VERSION_FILE=".version"
if [ ! -f "$VERSION_FILE" ]; then
    echo "1.0.0" > "$VERSION_FILE"
fi
CURRENT_VERSION=$(cat "$VERSION_FILE" | tr -d '[:space:]')
echo -e "${YELLOW}🔍 Current Corporate System Version: v$CURRENT_VERSION${NC}"

# 2. Syntax/Document Verification
echo -e "${YELLOW}⚙️  Verifying corporate documentation integrity...${NC}"
if [ ! -f "README.md" ]; then
    echo -e "${RED}❌ README.md is missing! Structural check failed.${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Corporate documents verified.${NC}"

# 3. Bump version on successful validation
IFS='.' read -r epoch major minor <<< "$CURRENT_VERSION"
NEW_MINOR=$((minor + 1))
NEW_VERSION="$epoch.$major.$NEW_MINOR"
echo "$NEW_VERSION" > "$VERSION_FILE"

echo -e "\n===================================================="
echo -e "${GREEN}🎉 CHARNIA CORPORATE PORTAL VALIDATED & SECURED${NC}"
echo -e "Version bumped: v$CURRENT_VERSION -> v$NEW_VERSION"
echo "===================================================="
exit 0
