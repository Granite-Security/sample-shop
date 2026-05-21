# auth-server:
  - ticket:
    - add db to the microservice
    - store users and roles
  - spike
    - store providers in db
    - explore on-behalf-of flow


spring:
security:
oauth2:
client:
registration:
azure:
client-id: "YOUR_CLIENT_ID"
client-secret: "YOUR_CLIENT_SECRET"
scope: openid, profile, email
authorization-grant-type: authorization_code
redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
provider:
azure:
issuer-uri: https://login.microsoftonline.com/common/v2.0
Architecture Diagram
graph TD