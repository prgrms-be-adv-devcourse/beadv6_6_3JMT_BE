package com.prompthub.apigateway.client;

/** authorize() 401(세션 무효) 또는 404(사용자 없음) — gateway는 둘 다 같게 취급한다. */
public class AuthorizeDeniedException extends RuntimeException {
}
