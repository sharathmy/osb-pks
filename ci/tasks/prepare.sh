#!/bin/bash
prepare(){
  if [ -n "$HOSTS" ]; then
    fake_dns
  fi
  if $SKIP_TLS; then
    import_self_signed_certs
  fi
}
