package io.github.infestdead.acam;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The viewer's side of remote control for a phone in camera mode: one short RTSP exchange on its
 * own connection, authenticated the same way as the stream. Blocks; call it off the main thread.
 * Plain Java, so it can be tried against {@link CamServer} on a desktop JVM.
 */
final class CamControl {
    private static final int TIMEOUT_MS = 4000;
    private static final Pattern FIELD = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private CamControl() { }

    /** Whether an address could be a phone in camera mode, i.e. worth asking. */
    static boolean mightBePhone(String url) {
        try {
            URI u = new URI(url);
            String info = u.getRawUserInfo();
            return "rtsp".equalsIgnoreCase(u.getScheme()) && info != null && info.startsWith(CamServer.USER + ":");
        } catch (Exception e) {
            return false;
        }
    }

    /** The camera's settings, or null if it is not a phone in camera mode. */
    static Map<String, String> get(String url) throws IOException {
        Map<String, String> state = call(url, "GET_PARAMETER", "acam\r\n");
        return "1".equals(state.get("acam")) ? state : null;
    }

    /** Change one setting; returns the settings as they now are. */
    static Map<String, String> set(String url, String name, String value) throws IOException {
        return call(url, "SET_PARAMETER", name + ": " + value + "\r\n");
    }

    private static Map<String, String> call(String url, String method, String body) throws IOException {
        URI u;
        try {
            u = new URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        String info = u.getRawUserInfo();
        if (info == null || !info.contains(":")) throw new IOException("no user and password in the address");
        String user = URLDecoder.decode(info.substring(0, info.indexOf(':')), "UTF-8");
        String password = URLDecoder.decode(info.substring(info.indexOf(':') + 1), "UTF-8");
        String host = u.getHost();
        if (host == null) throw new IOException("no host");
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        int port = u.getPort() > 0 ? u.getPort() : 554;
        // The request names the address without the password in it.
        String target = "rtsp://" + u.getHost() + ":" + port + (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath());

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            send(out, method, target, 1, null, body);
            Response r = Response.read(in);
            if (r.status == 401) {
                Map<String, String> challenge = new HashMap<>();
                Matcher m = FIELD.matcher(r.header("www-authenticate"));
                while (m.find()) challenge.put(m.group(1).toLowerCase(Locale.US), m.group(2));
                String realm = challenge.get("realm"), nonce = challenge.get("nonce");
                if (realm == null || nonce == null) throw new IOException("no digest challenge");
                String response = md5(md5(user + ":" + realm + ":" + password) + ":" + nonce + ":" + md5(method + ":" + target));
                send(out, method, target, 2, "Digest username=\"" + user + "\", realm=\"" + realm + "\", nonce=\""
                        + nonce + "\", uri=\"" + target + "\", response=\"" + response + "\"", body);
                r = Response.read(in);
            }
            if (r.status != 200) throw new IOException("camera answered " + r.status);
            Map<String, String> state = new HashMap<>();
            for (String line : r.body.split("\r?\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) state.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
            return state;
        }
    }

    private static void send(OutputStream out, String method, String target, int cseq, String auth, String body)
            throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        String head = method + " " + target + " RTSP/1.0\r\nCSeq: " + cseq + "\r\nUser-Agent: ACam\r\n"
                + (auth == null ? "" : "Authorization: " + auth + "\r\n")
                + "Content-Type: text/parameters\r\nContent-Length: " + content.length + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.flush();
    }

    private static final class Response {
        int status;
        String body = "";
        final Map<String, String> headers = new HashMap<>();

        String header(String name) {
            String v = headers.get(name);
            return v == null ? "" : v;
        }

        static Response read(InputStream in) throws IOException {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            int run = 0;
            while (run < 4) {
                int b = in.read();
                if (b < 0 || text.size() > 8192) throw new IOException("no answer");
                text.write(b);
                run = b == (run % 2 == 0 ? '\r' : '\n') ? run + 1 : (b == '\r' ? 1 : 0);
            }
            String[] lines = new String(text.toByteArray(), StandardCharsets.UTF_8).split("\r\n");
            String[] start = lines[0].split(" ");
            if (start.length < 2 || !start[0].startsWith("RTSP/")) throw new IOException("not RTSP");
            Response r = new Response();
            try {
                r.status = Integer.parseInt(start[1]);
            } catch (NumberFormatException e) {
                throw new IOException("bad status");
            }
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US), lines[i].substring(colon + 1).trim());
                }
            }
            int length;
            try {
                length = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException e) {
                throw new IOException("bad length");
            }
            if (length < 0 || length > 65536) throw new IOException("bad length");
            byte[] body = new byte[length];
            for (int i = 0; i < length; i++) {
                int b = in.read();
                if (b < 0) throw new IOException("cut short");
                body[i] = (byte) b;
            }
            r.body = new String(body, StandardCharsets.UTF_8);
            return r;
        }
    }

    private static String md5(String s) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte x : MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8))) {
                hex.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
