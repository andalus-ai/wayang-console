package ai.wayang.console.application;

import jakarta.enterprise.context.ApplicationScoped;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal HTTPS/1.1 POST transport that connects to the exact validated DNS answer.
 * It does not follow redirects and reads only a bounded response header block.
 */
@ApplicationScoped
public class RestrictedHttpsWebhookTransport implements ReconciliationSlaWebhookTransport {
    private static final int MAX_RESPONSE_HEADERS = 16 * 1024;
    private static final int MAX_STATUS_LINE = 4096;

    @Override
    public Response post(URI endpoint, Map<String, String> headers, byte[] body, Duration timeout) {
        if (endpoint == null || body == null || timeout == null
                || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Invalid webhook transport request");
        }
        String host = endpoint.getHost();
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || host == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || endpoint.getPort() != -1 && endpoint.getPort() != 443) {
            throw new IllegalArgumentException("Only HTTPS DNS endpoints on port 443 are supported");
        }
        int timeoutMillis = Math.toIntExact(timeout.toMillis());
        try {
            List<InetAddress> addresses = List.of(InetAddress.getAllByName(host));
            if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublicUnicast(address))) {
                throw new IllegalArgumentException("Webhook host resolves to a restricted network address");
            }
            IOException lastFailure = null;
            for (InetAddress address : addresses) {
                try {
                    return postToAddress(endpoint, host, address, headers, body, timeoutMillis);
                } catch (IOException failure) {
                    lastFailure = failure;
                }
            }
            throw new IllegalStateException("Webhook endpoint connection failed",
                    lastFailure == null ? new IOException("No usable address") : lastFailure);
        } catch (IOException failure) {
            throw new IllegalStateException("Webhook endpoint resolution failed", failure);
        }
    }

    private Response postToAddress(URI endpoint, String host, InetAddress address,
            Map<String, String> headers, byte[] body, int timeoutMillis) throws IOException {
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(address, 443), timeoutMillis);
            raw.setSoTimeout(timeoutMillis);
            SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tls = (SSLSocket) sslSocketFactory.createSocket(raw, host, 443, true);
            try (tls) {
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName(host)));
                tls.setSSLParameters(parameters);
                tls.setSoTimeout(timeoutMillis);
                tls.startHandshake();

                BufferedOutputStream output = new BufferedOutputStream(tls.getOutputStream());
                String path = endpoint.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                StringBuilder request = new StringBuilder()
                        .append("POST ").append(path).append(" HTTP/1.1\r\n")
                        .append("Host: ").append(host).append("\r\n")
                        .append("Connection: close\r\n")
                        .append("Content-Length: ").append(body.length).append("\r\n");
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (!header.getKey().matches("[A-Za-z0-9-]{1,80}")
                            || header.getValue() == null
                            || header.getValue().contains("\r") || header.getValue().contains("\n")) {
                        throw new IllegalArgumentException("Invalid webhook request header");
                    }
                    if (!header.getKey().equalsIgnoreCase("Host")
                            && !header.getKey().equalsIgnoreCase("Content-Length")
                            && !header.getKey().equalsIgnoreCase("Connection")) {
                        request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
                    }
                }
                request.append("\r\n");
                output.write(request.toString().getBytes(StandardCharsets.US_ASCII));
                output.write(body);
                output.flush();

                BufferedInputStream input = new BufferedInputStream(tls.getInputStream());
                String status = readLine(input, MAX_STATUS_LINE);
                if (status == null || !status.matches("HTTP/1\\.[01] [0-9]{3}.*")) {
                    throw new IOException("Invalid HTTPS response status line");
                }
                int statusCode = Integer.parseInt(status.substring(9, 12));
                int consumed = status.length() + 2;
                while (true) {
                    String line = readLine(input, MAX_RESPONSE_HEADERS - consumed);
                    if (line == null) throw new IOException("Incomplete HTTPS response headers");
                    consumed += line.length() + 2;
                    if (consumed > MAX_RESPONSE_HEADERS) throw new IOException("HTTPS response headers exceed limit");
                    if (line.isEmpty()) break;
                }
                return new Response(statusCode);
            }
        }
    }

    private static String readLine(BufferedInputStream input, int maxBytes) throws IOException {
        if (maxBytes <= 0) throw new IOException("HTTPS response headers exceed limit");
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (line.size() <= maxBytes) {
            int current = input.read();
            if (current < 0) return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
            }
            line.write(current);
            previous = current;
        }
        throw new IOException("HTTPS response line exceeds limit");
    }

    private static boolean isPublicUnicast(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            // Reject IPv6 unique-local, deprecated site-local, and IPv4-mapped addresses.
            // Only global-unicast 2000::/3 is accepted. Reject ULA, link/site-local,
            // documentation, transition, mapped, and other special-purpose IPv6 ranges.
            if ((first & 0xe0) != 0x20
                    || (first == 0xfe && (second & 0xc0) == 0x80)
                    || (first == 0xfe && (second & 0xc0) == 0xc0)
                    || (first == 0x20 && (bytes[1] & 0xff) == 0x01
                        && ((bytes[2] & 0xff) == 0x00 || (bytes[2] & 0xff) == 0x0d))
                    || (first == 0x20 && (bytes[1] & 0xff) == 0x02)) return false;
            return true;
        }
        int a = bytes[0] & 0xff, b = bytes[1] & 0xff, c = bytes[2] & 0xff;
        if (a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 100 && b >= 64 && b <= 127)
                || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && (b == 0 || b == 168))
                || (a == 198 && (b == 18 || b == 19 || b == 51))
                || (a == 203 && b == 0 && c == 113)) return false;
        return true;
    }
}
