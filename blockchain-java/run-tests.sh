#!/bin/bash
# Quick Test Runner Script for Blockchain Java

set -e

echo "=========================================="
echo "  Blockchain Java - Test Suite"
echo "=========================================="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

cd "$(dirname "$0")"

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}ERROR: Maven is not installed${NC}"
    exit 1
fi

echo "Maven version:"
mvn --version
echo ""

# Display menu
echo "Select test suite to run:"
echo "1. All Simple Tests (43 tests - ~2 seconds)"
echo "2. Transaction Tests (6 tests)"
echo "3. AccountState Tests (13 tests)"
echo "4. Block Tests (11 tests)"
echo "5. Mempool Tests (13 tests)"
echo "6. Compile Only (no tests)"
echo "7. Clean Build (compile + all tests)"
echo ""

read -p "Enter choice [1-7]: " choice

case $choice in
    1)
        echo -e "${YELLOW}Running: All Simple Tests${NC}"
        mvn test -Dtest=Simple*
        ;;
    2)
        echo -e "${YELLOW}Running: Transaction Tests${NC}"
        mvn test -Dtest=SimpleTransactionTest
        ;;
    3)
        echo -e "${YELLOW}Running: AccountState Tests${NC}"
        mvn test -Dtest=SimpleAccountStateTest
        ;;
    4)
        echo -e "${YELLOW}Running: Block Tests${NC}"
        mvn test -Dtest=SimpleBlockTest
        ;;
    5)
        echo -e "${YELLOW}Running: Mempool Tests${NC}"
        mvn test -Dtest=SimpleMempoolTest
        ;;
    6)
        echo -e "${YELLOW}Running: Compile Only${NC}"
        mvn compile
        ;;
    7)
        echo -e "${YELLOW}Running: Clean Build + All Tests${NC}"
        mvn clean compile test
        ;;
    *)
        echo -e "${RED}Invalid choice${NC}"
        exit 1
        ;;
esac

echo ""
echo -e "${GREEN}=========================================="
echo "  ✅ Test execution completed"
echo "==========================================${NC}"
