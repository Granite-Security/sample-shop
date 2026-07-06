FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . ./
RUN npm run build

FROM nginx:stable-alpine
# envsubst (from gettext) renders the runtime config at container start (Correction 4).
RUN apk add --no-cache gettext
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
COPY docker-entrypoint.sh /docker-entrypoint-granite.sh
RUN chmod +x /docker-entrypoint-granite.sh
ENTRYPOINT ["/docker-entrypoint-granite.sh"]
