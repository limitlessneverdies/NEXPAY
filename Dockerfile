FROM node:24-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production HOST=0.0.0.0 PORT=8787 DATA_DIR=/state MONEY_MODE=test ENABLE_WEB_WALLET=false
COPY server ./server
COPY web ./web
RUN mkdir -p /state /app/server/downloads && chown -R node:node /state /app/server/downloads
USER node
VOLUME /state
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s CMD node -e "fetch('http://127.0.0.1:8787/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "server/src/server.mjs"]
