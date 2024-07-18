#!/bin/zsh

zsh /workspace/.devcontainer/post-create.zsh

sudo mkdir -p /usr/local/transitclock/cache
sudo chown -R vscode:vscode /usr/local/transitclock


echo " From INSIDE Looks like everything works as expected!"