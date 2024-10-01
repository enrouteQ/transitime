```
docker run --rm -it --network transitclock_default -v ./configs:/usr/local/transitclock/config -v ./tomcat-logs:/usr/local/tomcat/logs -p 8080:8080 tc:web catalina
```

```
docker run --rm  -it --network transitclock_default -v ./configs:/usr/local/transitclock/config -v ./logs:/usr/local/transitclock/logs --name core tc:test core
```

```
TransitClock4dmin
```

```
docker run --rm  -it -v ./configs:/usr/local/transitclock/config -v ./logs:/usr/local/transitclock/logs --name core transitclock:core load
```


# ZSH on Amazon EC2

```
sudo yum update

# Installing ZSH
sudo yum -y install zsh

# Check ZSH has been installed
zsh --version

# Install "util-linux-user" because "chsh" is not available by default
# See https://superuser.com/a/1389273/599050
sudo yum install util-linux-user

# Change default shell for current user, most of the times it would be "ec2-user"
# Or use "sudo chsh -s $(which zsh) $(whoami)" to be sure
chsh -s "$(which zsh)"

# Install oh-my-zsh from https://github.com/ohmyzsh/ohmyzsh#basic-installation

# OPTIONAL

# I recommend you to install the following plugins which make your life easier and more colorful

# Assuming you have git installed
# If not, simply do "sudo yum install git -y"
git clone https://github.com/zsh-users/zsh-syntax-highlighting.git ${ZSH_CUSTOM:-~/.oh-my-zsh/custom}/plugins/zsh-syntax-highlighting
git clone https://github.com/zsh-users/zsh-autosuggestions ${ZSH_CUSTOM:-~/.oh-my-zsh/custom}/plugins/zsh-autosuggestions

# And then add them to "~/.zshrc" file
plugins=(git zsh-autosuggestions zsh-syntax-highlighting)

```