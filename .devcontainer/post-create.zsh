#!/bin/zsh
echo "Installing OhMyZSH with purepower"

git clone https://github.com/romkatv/powerlevel10k.git /home/vscode/.oh-my-zsh/custom/themes/powerlevel10k
cd /home/vscode
curl -fsSLO https://raw.githubusercontent.com/romkatv/dotfiles-public/2d27deefd928175b80d681fc06eb2791848591fd/.purepower

cp /workspace/.devcontainer/.zshrc /home/vscode/.zshrc
sudo chown vscode /home/vscode/.cache
sudo chown vscode /home/vscode/.purepower
sudo chown vscode /home/vscode/.zshrc

echo "OMZ Installed."

echo "Installing maven"
sudo apt-get update
sudo apt-get install -y maven