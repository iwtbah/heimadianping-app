FROM openresty/openresty:1.27.1.2-alpine

LABEL org.opencontainers.image.title="heimadianping-openresty"

COPY nginx.conf /usr/local/openresty/nginx/conf/nginx.conf
COPY conf.d /etc/nginx/conf.d
COPY html /usr/local/openresty/nginx/html
COPY lua /usr/local/openresty/nginx/lua
