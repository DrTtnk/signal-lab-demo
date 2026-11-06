FROM node:24.21.0-alpine AS build
WORKDIR /work
COPY package.json package-lock.json ./
COPY frontend/package.json frontend/package.json
COPY tests/e2e/package.json tests/e2e/package.json
COPY backend/java/package.json backend/java/package.json
COPY backend/python/package.json backend/python/package.json
COPY backend/rust/package.json backend/rust/package.json
COPY tools/fixtures/package.json tools/fixtures/package.json
COPY clients/angular/package.json clients/angular/package.json
COPY clients/native/package.json clients/native/package.json
RUN npm ci
COPY frontend ./frontend
COPY contracts/v1 ./contracts/v1
COPY clients ./clients
RUN npm run build --workspace frontend --workspace @signal-lab/angular --workspace @signal-lab/native
FROM nginxinc/nginx-unprivileged:1.29.3-alpine
USER root
RUN apk add --no-cache curl
USER nginx
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /work/frontend/dist /usr/share/nginx/html
COPY --from=build /work/clients/angular/dist/browser /usr/share/nginx/html/angular
COPY --from=build /work/clients/native/dist /usr/share/nginx/html/native
EXPOSE 8080
