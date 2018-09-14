FROM openjdk:8-jdk
RUN apt-get update && apt-get install -y jq netcat apt-transport-https
RUN curl -s https://packages.cloud.google.com/apt/doc/apt-key.gpg |  apt-key add -
RUN  touch /etc/apt/sources.list.d/kubernetes.list 
RUN echo "deb http://apt.kubernetes.io/ kubernetes-xenial main" |  tee -a /etc/apt/sources.list.d/kubernetes.list
RUN  apt-get update && apt-get install kubectl -y
RUN apt-get update && apt-get install -y build-essential zlibc zlib1g-dev ruby ruby-dev openssl libxslt-dev libxml2-dev libssl-dev readline-common libreadline-dev libyaml-dev libsqlite3-dev sqlite3 
RUN curl -Lo ./bosh https://s3.amazonaws.com/bosh-cli-artifacts/bosh-cli-5.1.2-linux-amd64 \
    && chmod +x ./bosh && cp bosh /usr/bin/bosh
RUN wget https://github.com/starkandwayne/eden/releases/download/v0.6.4/eden-linux-amd64 && chmod +x eden-linux-amd64 && mv eden-linux-amd64 /usr/bin/eden
RUN apt-get update && apt-get install bzr
