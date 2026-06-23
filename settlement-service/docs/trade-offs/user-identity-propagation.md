# User Identity Propagation

The API Gateway handles authentication and authorization. Each service only reads the
identity the gateway forwards as headers.

## How

- The gateway verifies the token and forwards `X-User-Id` and `X-User-Role`.
- Services read them with `@RequestHeader`. No security filter per service. (same as order-service)
- Admin endpoints check `X-User-Role == ADMIN`. The check lives in one place, an interceptor on `/admin/**`.

```java
public ... run(@RequestHeader("X-User-Id") UUID userId,
               @RequestHeader("X-User-Role") String role, ...) { ... }
```

## Preconditions

This setup trusts the headers, so it is only safe when both hold:

- Services are reachable only through the gateway. Internal services are never exposed directly.
- The gateway strips any `X-User-*` headers sent by the client, then sets its own verified values.

If either is missing, a client can forge `X-User-Role: ADMIN` and bypass the admin check.

## Options

| Option | Pros | Cons |
| --- | --- | --- |
| Header propagation (chosen) | No token logic or signing keys in the service; simple | Needs gateway as sole entry point + header stripping |
| Forward the JWT, each service verifies | Service checks the origin itself; weaker trust assumption | Verification logic and key distribution duplicated per service |
| Signed headers / mTLS | Blocks header forgery, proves origin | Key and certificate management overhead |

## Why this fits us

- We run independent services behind one shared gateway. Keeping token verification at the gateway
  means a service never needs the signing keys or the verification code.
- The services sit on an internal network reachable only through the gateway, so trusting the
  forwarded headers is reasonable without extra signing.
- Settlement's needs are narrow: an admin triggers the batch, a seller reads their own data. We only
  need who and what role, not the full set of token claims.
- order-service already reads identity this way, so staying with headers keeps the services uniform.

If we later need stronger guarantees, signed headers or mTLS is the next step up.
