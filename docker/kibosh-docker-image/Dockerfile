FROM golang
RUN apt-get update && apt-get -y install unzip
RUN mkdir -p /go/src/github.com/cf-platform-eng/kibosh-master/charts
RUN cd /go/src/github.com/cf-platform-eng && wget https://github.com/cf-platform-eng/kibosh/archive/master.zip && unzip master.zip
RUN go get -u github.com/onsi/ginkgo/ginkgo
RUN go get -u github.com/onsi/gomega
RUN go get -u github.com/maxbrunsfeld/counterfeiter
RUN go get -u github.com/golang/dep/cmd/dep
RUN go get -u github.com/gosuri/uitable
RUN cd /go/src/github.com/cf-platform-eng/kibosh-master && \
    make bootstrap && \
    make linux && \
    mv kibosh.linux /usr/bin/kibosh && \
    make build-bazaar-linux && \
    cp bazaar.linux /usr/bin/bazaar
COPY ./start.sh /usr/bin/start
RUN chmod +x /usr/bin/start
RUN mkdir -p /home/charts
