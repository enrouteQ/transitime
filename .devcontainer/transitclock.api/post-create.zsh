#!/bin/zsh

zsh /workspace/.devcontainer/post-create.zsh
echo " "
echo "Installing tomcat dependencies..."

# Tomcat 
sudo mkdir -p /usr/local/tomcat
sudo chown -R vscode:vscode /usr/local/tomcat
cd /usr/local/tomcat
curl -fSL "https://archive.apache.org/dist/tomcat/tomcat-8/v8.5.93/bin/apache-tomcat-8.5.93.tar.gz" -o tomcat.tar.gz
tar -xvf tomcat.tar.gz --strip-components=1

echo " From INSIDE Looks like everything works as expected!"