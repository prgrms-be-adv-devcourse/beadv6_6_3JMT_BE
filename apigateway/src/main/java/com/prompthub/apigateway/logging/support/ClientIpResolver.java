package com.prompthub.apigateway.logging.support;

import java.net.InetSocketAddress;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import static com.prompthub.apigateway.logging.GatewayLogConstants.UNKNOWN;
import static com.prompthub.apigateway.logging.GatewayLogConstants.X_FORWARDED_FOR_HEADER;

@Component
public class ClientIpResolver {

    public String resolve(ServerWebExchange exchange) {
        String forwarded = lastValidForwardedAddress(exchange.getRequest().getHeaders());
        if (forwarded != null) {
            return forwarded;
        }

        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return UNKNOWN;
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    private String lastValidForwardedAddress(HttpHeaders headers) {
        List<String> values = headers.get(X_FORWARDED_FOR_HEADER);
        if (values == null) {
            return null;
        }

        for (int valueIndex = values.size() - 1; valueIndex >= 0; valueIndex--) {
            String[] candidates = values.get(valueIndex).split(",");
            for (int candidateIndex = candidates.length - 1; candidateIndex >= 0; candidateIndex--) {
                String candidate = candidates[candidateIndex].trim();
                if (isLiteralIp(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean isLiteralIp(String candidate) {
        return isIpv4(candidate) || isIpv6(candidate);
    }

    private boolean isIpv4(String candidate) {
        String[] parts = candidate.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }

        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(this::isAsciiDigit)) {
                return false;
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private boolean isIpv6(String candidate) {
        String normalized = normalizeEmbeddedIpv4(candidate);
        if (normalized == null || !normalized.contains(":") || normalized.contains(":::")) {
            return false;
        }

        int compressedIndex = normalized.indexOf("::");
        if (compressedIndex != normalized.lastIndexOf("::")) {
            return false;
        }

        String[] compressedParts = normalized.split("::", -1);
        if (compressedParts.length > 2) {
            return false;
        }

        int groupCount = 0;
        for (String compressedPart : compressedParts) {
            String[] groups = compressedPart.split(":", -1);
            for (String group : groups) {
                if (group.isEmpty()) {
                    if (!compressedPart.isEmpty()) {
                        return false;
                    }
                    continue;
                }
                if (group.length() > 4 || !group.chars().allMatch(this::isHexDigit)) {
                    return false;
                }
                groupCount++;
            }
        }

        return compressedIndex >= 0 ? groupCount < 8 : groupCount == 8;
    }

    private String normalizeEmbeddedIpv4(String candidate) {
        if (!candidate.contains(".")) {
            return candidate;
        }

        int lastColon = candidate.lastIndexOf(':');
        if (lastColon < 0 || !isIpv4(candidate.substring(lastColon + 1))) {
            return null;
        }
        return candidate.substring(0, lastColon + 1) + "0:0";
    }

    private boolean isHexDigit(int character) {
        return character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f'
                || character >= 'A' && character <= 'F';
    }

    private boolean isAsciiDigit(int character) {
        return character >= '0' && character <= '9';
    }
}
