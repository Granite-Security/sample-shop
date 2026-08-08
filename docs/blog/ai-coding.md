Goal: create a minimum SPA (Single Page Application) ui that has the minimum complexity possible and yet be able 
to do everything you would normally expect from a logged in user. 

You basically have 2 choices (design decisions): Either keep the session info 

It started from a simple challenge: create a bunch of microservices that would follow the OAuth2 standards:
an Oauth2 Resources Server
and OAuth2 Authorization Server
and an Oauth2 Client Server
and minimize the code needed for the SPA. We wanted the SPA to be as simple (stupid) as possible. 
The first thing that came to our mind was to use a relayToken service from the Spring Boot Gateway project, which would redirect the user
to the Authorization Server

We first used KeyCloak as OAuth2 Authorization Server in my https://s4v3.net project, but very soon we realized we
hit a wall because it was not flexible enough.  We could not have a Gateway Spring boot that would consistently do
token relays.

I have observed a lot of technical debt while I was vibing the sample shop project.
