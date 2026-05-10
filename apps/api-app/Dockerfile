FROM livekit/livekit-server:latest

EXPOSE 7880
EXPOSE 7881
EXPOSE 7882/udp

CMD ["--dev", "--bind", "0.0.0.0", "--node-ip", "127.0.0.1"]

#docker run --rm \
 #  -p 7880:7880 \
 #  -p 7881:7881 \
 #  -p 7882:7882/udp \
 #  livekit/livekit-server \
 #  --dev \
 #  --bind 0.0.0.0 \
 #  --node-ip 127.0.0.1