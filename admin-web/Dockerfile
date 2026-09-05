FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG NEXT_PUBLIC_API_BASE_URL
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
RUN npm run build

FROM node:22-alpine
RUN addgroup -S campusguard && adduser -S -G campusguard -u 10001 campusguard
WORKDIR /app
ENV NODE_ENV=production HOST=0.0.0.0 PORT=3000
COPY --from=build --chown=campusguard:campusguard /app/dist/standalone/ ./
# vinext's standalone emitter currently omits its non-optional React peer
# runtime. Add only those small runtime packages; the full build tree stays out.
COPY --from=build --chown=campusguard:campusguard /app/node_modules/react/ ./node_modules/react/
COPY --from=build --chown=campusguard:campusguard /app/node_modules/react-dom/ ./node_modules/react-dom/
COPY --from=build --chown=campusguard:campusguard /app/node_modules/scheduler/ ./node_modules/scheduler/
USER 10001
EXPOSE 3000
CMD ["node", "server.js"]
