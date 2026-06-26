SCRIPT_PATH=$(realpath ${BASH_SOURCE})
sudo rm -f $SCRIPT_PATH

echo "================================="
echo "       Welcome to AI CLI         "
echo "  What would you like to run?    "
echo "================================="
echo "1. Claude Code"
echo "2. Codex CLI"
echo "3. Gemini CLI"
echo "4. Github Copilot"
echo "5. Just a Shell"
echo "================================="

read -p "Enter your choice [1-5]: " choice

case $choice in
    1)
        claude
        ;;
    2)
        codex
        ;;
    3)
        gemini
        ;;
    4)
        copilot
        ;;
    5)
        echo -e "\nWelcome to Ubuntu in UserLAnd!"
        ;;
    *)
        echo -e "\nInvalid option! Just starting a standard shell."
        echo -e "\nWelcome to Ubuntu in UserLAnd!"
        ;;
esac