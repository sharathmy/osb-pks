FROM appropriate/curl:latest
ADD https://github.com/stedolan/jq/releases/download/jq-1.5/jq-linux64 /usr/bin/jq
ADD ./route_emit.sh /usr/bin/route_emit 
RUN chmod +x /usr/bin/route_emit && chmod +x /usr/bin/jq
CMD /usr/bin/route_emit
